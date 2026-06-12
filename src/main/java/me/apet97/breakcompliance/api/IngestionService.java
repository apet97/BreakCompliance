package me.apet97.breakcompliance.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import me.apet97.breakcompliance.clockify.ClockifyApiException;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.clockify.HolidayFetcher;
import me.apet97.breakcompliance.clockify.TimeOffFetcher;
import me.apet97.breakcompliance.clockify.UserDirectoryFetcher;
import me.apet97.breakcompliance.config.AsyncConfig;
import me.apet97.breakcompliance.config.MetricsConfig;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.entities.WorkspaceTimeOff;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceTimeOffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates a Detailed-Report ingestion in three phases so the long
 * Clockify HTTP call never holds a DB connection:
 *
 * <ol>
 *   <li><b>prepare</b> (txn) — read installation, verify ACTIVE, decrypt
 *       token, backfill {@code reportsUrl} if missing, insert an
 *       {@code IngestionRun} row in {@code RUNNING} state.
 *   <li><b>fetch</b> (no txn) — the long HTTP call to Clockify's reports
 *       endpoint. Can take 30s+ with retries.
 *   <li><b>finalize</b> (txn) — upsert {@link TimeEntry} rows in batches
 *       and mark the run {@code COMPLETED} or {@code FAILED}.
 * </ol>
 *
 * <p>Why three phases: under multi-tenant load, holding a Hikari
 * connection through the HTTP call starves other workspaces. The pool
 * is sized for short transactions, not for HTTP-bound work.
 *
 * <p>Each phase runs under {@link TransactionTemplate} with default
 * {@code REQUIRED} propagation, so test code that wraps the call in an
 * {@code @Transactional} test method still observes atomic, rollback-
 * safe behaviour.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int FINALIZE_FLUSH_BATCH = 200;

    private final InstallationRepository installationRepo;
    private final TimeEntryRepository timeEntryRepo;
    private final IngestionRunRepository runRepo;
    private final DetailedReportFetcher fetcher;
    private final TokenCodec codec;
    private final TransactionTemplate tx;
    private final TransactionTemplate afterRollbackTx;
    private final Executor ingestExecutor;
    private final MeterRegistry meters;
    private final Timer runDuration;
    private final WorkspaceSettingsRepository workspaceSettings;
    private final HolidayFetcher holidayFetcher;
    private final TimeOffFetcher timeOffFetcher;
    private final WorkspaceHolidayRepository holidayRepo;
    private final WorkspaceTimeOffRepository timeOffRepo;
    private final UserDirectoryFetcher userDirectoryFetcher;

    public IngestionService(
            InstallationRepository installationRepo,
            TimeEntryRepository timeEntryRepo,
            IngestionRunRepository runRepo,
            DetailedReportFetcher fetcher,
            TokenCodec codec,
            PlatformTransactionManager txManager,
            @Qualifier(AsyncConfig.INGEST_EXECUTOR_BEAN) Executor ingestExecutor,
            MeterRegistry meters,
            WorkspaceSettingsRepository workspaceSettings,
            HolidayFetcher holidayFetcher,
            TimeOffFetcher timeOffFetcher,
            WorkspaceHolidayRepository holidayRepo,
            WorkspaceTimeOffRepository timeOffRepo,
            UserDirectoryFetcher userDirectoryFetcher) {
        this.installationRepo = installationRepo;
        this.timeEntryRepo = timeEntryRepo;
        this.runRepo = runRepo;
        this.fetcher = fetcher;
        this.codec = codec;
        this.tx = new TransactionTemplate(txManager);
        this.afterRollbackTx = new TransactionTemplate(txManager);
        this.afterRollbackTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ingestExecutor = ingestExecutor;
        this.meters = meters;
        this.workspaceSettings = workspaceSettings;
        this.holidayFetcher = holidayFetcher;
        this.timeOffFetcher = timeOffFetcher;
        this.holidayRepo = holidayRepo;
        this.timeOffRepo = timeOffRepo;
        this.userDirectoryFetcher = userDirectoryFetcher;
        this.runDuration = Timer.builder(MetricsConfig.INGEST_RUN_DURATION)
                .description("Total time spent in IngestionService.executeRun")
                .register(meters);
    }

    public IngestionRun ingest(String workspaceId, LocalDate from, LocalDate to) {
        return ingest(workspaceId, from, to, null);
    }

    // P1.3 — best-effort lookup so a transient DB error during fetch
    // doesn't break the ingest. Defaults to "fetch everything" (false),
    // matching the historical behaviour.
    private boolean isExcludeUnsubmittedEnabled(String workspaceId) {
        try {
            return workspaceSettings.findById(workspaceId)
                    .map(WorkspaceSettings::isExcludeUnsubmittedEntries)
                    .orElse(false);
        } catch (RuntimeException e) {
            log.debug("ingest.exclude-unsubmitted.lookup-failed workspace={} reason={}",
                    workspaceId, e.getClass().getSimpleName());
            return false;
        }
    }

    public IngestionRun ingest(String workspaceId, LocalDate from, LocalDate to, String reportsUrlOverride) {
        PreparedRun prepared = prepareRunTransactional(workspaceId, from, to, reportsUrlOverride);

        List<Map<String, Object>> entries;
        try {
            entries = fetcher.fetch(workspaceId, prepared.reportsUrl(), prepared.token(), from, to,
                    isExcludeUnsubmittedEnabled(workspaceId));
        } catch (ClockifyApiException e) {
            tx.execute(status -> markRunFailed(workspaceId, prepared.runId(), "ClockifyApi:" + e.statusCode()));
            log.warn("ingestion.failed.clockify workspace={} status={}", workspaceId, e.statusCode());
            // Propagate so the controller can map status-specific responses
            // (notably 401 → user-friendly 503 reports_unavailable).
            throw e;
        } catch (RuntimeException e) {
            IngestionRun failed = tx.execute(status -> markRunFailed(
                    workspaceId, prepared.runId(), e.getClass().getSimpleName()));
            log.warn("ingestion.failed workspace={} reason={}", workspaceId, e.getClass().getSimpleName(), e);
            return failed;
        }

        IngestionRun completed = tx.execute(status -> finalizeRun(workspaceId, prepared.runId(), entries));
        // P1.1 / P1.2 — refresh suppression cache after a successful sync
        // ingest, mirroring the async path. Best-effort.
        try {
            refreshSuppressionForWindow(workspaceId, prepared.token(), from, to);
        } catch (RuntimeException e) {
            log.info("ingestion.suppression.refresh-failed workspace={} reason={}",
                    workspaceId, e.getClass().getSimpleName());
        }
        return completed;
    }

    /**
     * Begin an ingest asynchronously. Synchronously prepares the run
     * (installation/active check, token decrypt, RUNNING row inserted) so
     * the controller can return 202 with a real {@code run.id} for the
     * sidebar to poll, then dispatches the long Clockify fetch + finalize
     * onto {@link AsyncConfig#INGEST_EXECUTOR_BEAN}. Exceptions raised in
     * prepare propagate to the caller (controller maps
     * {@link InstallationInactiveException} → 503 installation_inactive);
     * exceptions raised during the async fetch are captured on the run
     * row as {@code errorCode} so the polling endpoint can surface them.
     */
    public IngestionRun beginAsync(
            String workspaceId, LocalDate from, LocalDate to, String reportsUrlOverride) {
        PreparedRun prepared = prepareRunTransactional(workspaceId, from, to, reportsUrlOverride);
        IngestionRun inFlight = runRepo
                .findById(new IngestionRun.Pk(workspaceId, prepared.runId()))
                .orElseThrow(() -> new IllegalStateException(
                        "prepared run vanished between insert and re-read: " + prepared.runId()));

        ingestExecutor.execute(() -> executeRun(
                workspaceId, prepared.runId(), prepared.token(), prepared.reportsUrl(), from, to));
        return inFlight;
    }

    /**
     * Begin an async ingest with a post-finalize hook. Same shape as
     * {@link #beginAsync} but the supplied {@code afterFinalize} callback
     * runs on the executor thread once the ingest committed cleanly (run
     * status {@code COMPLETED}). The callback receives the run id as a
     * parameter so it can back-reference the ingest in any side-effect
     * (e.g. marking refresh signals consumed by run-id). Callback
     * exceptions are caught and logged so the run record stays
     * trustworthy — a downstream side effect failing must not corrupt
     * the upstream ingest's terminal state. Used by the refresh-signal
     * trigger to drive {@code FindingsService.evaluateAndReplace} after
     * the time-entry upsert without blocking the controller thread; the
     * sidebar polls {@code GET /api/ingest/runs/{id}} for status.
     */
    public IngestionRun beginAsyncForRefresh(
            String workspaceId,
            LocalDate from,
            LocalDate to,
            String reportsUrlOverride,
            java.util.function.Consumer<String> afterFinalize) {
        PreparedRun prepared = prepareRunTransactional(workspaceId, from, to, reportsUrlOverride);
        IngestionRun inFlight = runRepo
                .findById(new IngestionRun.Pk(workspaceId, prepared.runId()))
                .orElseThrow(() -> new IllegalStateException(
                        "prepared run vanished between insert and re-read: " + prepared.runId()));

        ingestExecutor.execute(() -> {
            executeRun(workspaceId, prepared.runId(), prepared.token(), prepared.reportsUrl(), from, to);
            if (afterFinalize == null) {
                return;
            }
            IngestionRun result = runRepo
                    .findById(new IngestionRun.Pk(workspaceId, prepared.runId()))
                    .orElse(null);
            if (result == null || result.getStatus() != IngestionStatus.COMPLETED) {
                return;
            }
            try {
                afterFinalize.accept(prepared.runId());
            } catch (RuntimeException e) {
                log.warn(
                        "ingestion.async.after-finalize-failed workspace={} runId={} reason={}",
                        workspaceId,
                        prepared.runId(),
                        e.getClass().getSimpleName(),
                        e);
            }
        });
        return inFlight;
    }

    private PreparedRun prepareRunTransactional(
            String workspaceId, LocalDate from, LocalDate to, String reportsUrlOverride) {
        try {
            PreparedRun prepared = tx.execute(status -> prepareRun(workspaceId, from, to, reportsUrlOverride));
            return Objects.requireNonNull(prepared, "prepareRun returned null");
        } catch (DataIntegrityViolationException e) {
            throw mapRunningRunRaceAfterRollback(workspaceId, from, to, e);
        }
    }

    private RuntimeException mapRunningRunRaceAfterRollback(
            String workspaceId, LocalDate from, LocalDate to, DataIntegrityViolationException cause) {
        IngestionRun existing = afterRollbackTx.execute(status -> runRepo
                .findFirstByWorkspaceIdAndStatusAndDateRangeStartAndDateRangeEnd(
                        workspaceId, IngestionStatus.RUNNING, from.toString(), to.toString())
                .orElse(null));
        if (existing != null) {
            throw new IngestionRunInProgressException(existing.getId());
        }
        return cause;
    }

    /**
     * Long, off-request portion of an ingest: the Clockify HTTP fetch and
     * the finalize batch upsert. Called by the executor dispatched from
     * {@link #beginAsync} — never throws to its caller; instead, every
     * failure is captured on the run row so the polling endpoint reports it.
     * Public so {@code IngestRunControllerTest} can drive it directly, but
     * production callers should use {@link #beginAsync}.
     */
    public void executeRun(
            String workspaceId,
            String runId,
            String token,
            String reportsUrl,
            LocalDate from,
            LocalDate to) {
        Timer.Sample sample = Timer.start(meters);
        List<Map<String, Object>> entries;
        try {
            entries = fetcher.fetch(workspaceId, reportsUrl, token, from, to,
                    isExcludeUnsubmittedEnabled(workspaceId));
        } catch (ClockifyApiException e) {
            tx.execute(status -> markRunFailed(workspaceId, runId, "ClockifyApi:" + e.statusCode()));
            meters.counter(MetricsConfig.INGEST_RUN_FAILED, "reason", "ClockifyApi:" + e.statusCode())
                    .increment();
            sample.stop(runDuration);
            log.warn("ingestion.async.failed.clockify workspace={} status={}", workspaceId, e.statusCode());
            return;
        } catch (RuntimeException e) {
            tx.execute(status -> markRunFailed(workspaceId, runId, e.getClass().getSimpleName()));
            meters.counter(MetricsConfig.INGEST_RUN_FAILED, "reason", e.getClass().getSimpleName())
                    .increment();
            sample.stop(runDuration);
            log.warn("ingestion.async.failed workspace={} reason={}", workspaceId, e.getClass().getSimpleName(), e);
            return;
        }
        try {
            IngestionRun finalized = tx.execute(status -> finalizeRun(workspaceId, runId, entries));
            sample.stop(runDuration);
            if (finalized != null) {
                meters.counter(MetricsConfig.INGEST_ENTRIES_PROCESSED).increment(finalized.getEntriesProcessed());
            }
        } catch (RuntimeException e) {
            tx.execute(status -> markRunFailed(workspaceId, runId, "Finalize:" + e.getClass().getSimpleName()));
            meters.counter(MetricsConfig.INGEST_RUN_FAILED, "reason", "Finalize:" + e.getClass().getSimpleName())
                    .increment();
            sample.stop(runDuration);
            log.warn("ingestion.async.failed.finalize workspace={} reason={}", workspaceId, e.getClass().getSimpleName(), e);
            return;
        }

        // P1.1 / P1.2 — best-effort suppression refresh. Failure here
        // doesn't fail the run (we'd already finalized successfully); the
        // worst case is "no suppression on this range" which matches
        // pre-P1.1 behaviour exactly.
        try {
            refreshSuppressionForWindow(workspaceId, token, from, to);
        } catch (RuntimeException e) {
            log.info("ingestion.suppression.refresh-failed workspace={} reason={}",
                    workspaceId, e.getClass().getSimpleName());
        }
    }

    /**
     * Fetch + upsert holidays + approved time-off for {@code [from, to]}.
     * The backend URL is read from the installation row (holidays + time-off
     * live on {@code api.clockify.me}, not the reports host). Idempotent —
     * upsert keys are stable so re-running for the same range overwrites
     * cleanly.
     */
    private void refreshSuppressionForWindow(
            String workspaceId, String token, LocalDate from, LocalDate to) {
        Installation install = installationRepo.findByWorkspaceId(workspaceId).orElse(null);
        if (install == null || install.getBackendUrl() == null || install.getBackendUrl().isBlank()) {
            return;
        }
        String backendUrl = install.getBackendUrl();
        java.time.Instant now = java.time.Instant.now();

        // Holidays — delete-then-upsert per window. Without the delete, a
        // holiday removed in Clockify would keep suppressing its date
        // until a manual wipe.
        List<HolidayFetcher.HolidayRow> holidays =
                holidayFetcher.fetch(workspaceId, backendUrl, token, from, to);
        tx.executeWithoutResult(status -> {
            holidayRepo.deleteByWorkspaceIdAndDateBetween(workspaceId, from, to);
            for (var row : holidays) {
                WorkspaceHoliday h = new WorkspaceHoliday();
                h.setWorkspaceId(workspaceId);
                h.setSourceId(row.sourceId());
                h.setDate(row.date());
                h.setAppliesToUserId(row.appliesToUserId());
                h.setName(row.name());
                h.setIngestedAt(now);
                holidayRepo.save(h);
            }
        });

        // Approved time-off — same replace-on-refetch story. A withdrawn
        // / rejected request stops suppressing on the next refresh.
        Instant windowStart = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<TimeOffFetcher.TimeOffRow> timeOff =
                timeOffFetcher.fetchApproved(workspaceId, backendUrl, token, from, to);
        tx.executeWithoutResult(status -> {
            timeOffRepo.deleteByWorkspaceIdAndStartAtLessThanAndEndAtGreaterThanEqual(
                    workspaceId, windowEnd, windowStart);
            for (var row : timeOff) {
                WorkspaceTimeOff t = new WorkspaceTimeOff();
                t.setWorkspaceId(workspaceId);
                t.setSourceId(row.sourceId());
                t.setUserId(row.userId());
                t.setStartAt(row.startAt());
                t.setEndAt(row.endAt());
                t.setStatus(row.status());
                t.setIngestedAt(now);
                timeOffRepo.save(t);
            }
        });

        // P2.3 — refresh stale userName columns. Single transaction over
        // the whole directory; the repo's bulk UPDATE is a no-op for
        // rows whose name already matches. Avoids "John Owner" lingering
        // after a user renames in Clockify.
        try {
            Map<String, String> directory =
                    userDirectoryFetcher.fetchActive(workspaceId, backendUrl, token);
            if (!directory.isEmpty()) {
                tx.executeWithoutResult(status -> {
                    for (var e : directory.entrySet()) {
                        timeEntryRepo.updateUserNameForUser(workspaceId, e.getKey(), e.getValue());
                    }
                });
            }
        } catch (RuntimeException e) {
            log.info("ingestion.userdir.reconcile-failed workspace={} reason={}",
                    workspaceId, e.getClass().getSimpleName());
        }
    }

    private PreparedRun prepareRun(
            String workspaceId, LocalDate from, LocalDate to, String reportsUrlOverride) {
        Installation install = installationRepo
                .findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalStateException(
                        "no installation for workspaceId=" + workspaceId + "; install the addon first"));
        if (install.getStatus() != InstallationStatus.ACTIVE) {
            // Lifecycle STATUS_CHANGED to INACTIVE means Clockify expects us
            // to stop all operations for this workspace. Bail before reading
            // the token or creating a run record.
            throw new InstallationInactiveException(workspaceId);
        }
        // Dedupe: refuse to start a second ingest for the same
        // (workspace, date range) while one is in-flight. Admins can
        // double-click "Refresh", and a webhook + manual trigger can
        // overlap. The DB partial unique index is the final guard for
        // concurrent callers that pass this optimistic pre-check together.
        runRepo.findFirstByWorkspaceIdAndStatusAndDateRangeStartAndDateRangeEnd(
                        workspaceId, IngestionStatus.RUNNING, from.toString(), to.toString())
                .ifPresent(existing -> {
                    throw new IngestionRunInProgressException(existing.getId());
                });
        String token =
                codec.decrypt(install.getAuthToken().getKeyId(), install.getAuthToken().getCipher());

        String reportsUrl = nonBlank(reportsUrlOverride);
        if (reportsUrl == null) {
            reportsUrl = nonBlank(install.getReportsUrl());
        } else if (nonBlank(install.getReportsUrl()) == null) {
            install.setReportsUrl(reportsUrl);
            installationRepo.save(install);
        }
        if (reportsUrl == null) {
            throw new IllegalStateException(
                    "no reportsUrl available for workspaceId=" + workspaceId
                            + "; the request JWT is missing the reportsUrl claim and the persisted"
                            + " installation has no cached value — re-open the addon so a fresh user"
                            + " token provides the workspace's region-specific reports endpoint");
        }

        IngestionRun run = newRun(workspaceId, from, to);
        run.setStatus(IngestionStatus.RUNNING);
        runRepo.saveAndFlush(run);
        return new PreparedRun(run.getId(), token, reportsUrl);
    }

    private IngestionRun finalizeRun(
            String workspaceId, String runId, List<Map<String, Object>> entries) {
        IngestionRun run = runRepo
                .findById(new IngestionRun.Pk(workspaceId, runId))
                .orElseThrow(() -> new IllegalStateException(
                        "ingestion run vanished between prepare and finalize: " + runId));
        int processed = 0;
        int batchCounter = 0;
        Instant ingestedAt = Instant.now();
        for (Map<String, Object> raw : entries) {
            upsertEntry(workspaceId, raw, ingestedAt);
            processed++;
            if (++batchCounter >= FINALIZE_FLUSH_BATCH) {
                // Periodic flush keeps the persistence context's first-level
                // cache bounded — a 100k-entry ingest would otherwise hold
                // every TimeEntry in memory until commit.
                timeEntryRepo.flush();
                batchCounter = 0;
            }
        }
        run.setStatus(IngestionStatus.COMPLETED);
        run.setEntriesProcessed(processed);
        run.setCompletedAt(Instant.now());
        log.info("ingestion.completed workspace={} entries={}", workspaceId, processed);
        return runRepo.saveAndFlush(run);
    }

    private IngestionRun markRunFailed(String workspaceId, String runId, String errorCode) {
        IngestionRun run = runRepo
                .findById(new IngestionRun.Pk(workspaceId, runId))
                .orElseThrow(() -> new IllegalStateException(
                        "ingestion run vanished before failure marker: " + runId));
        run.setStatus(IngestionStatus.FAILED);
        run.setErrorCode(errorCode);
        run.setCompletedAt(Instant.now());
        return runRepo.saveAndFlush(run);
    }

    private void upsertEntry(String workspaceId, Map<String, Object> raw, Instant ingestedAt) {
        String sourceEntryId = stringValue(raw.get("_id"), raw.get("id"));
        if (sourceEntryId == null) {
            return; // skip malformed
        }
        TimeEntry entry = timeEntryRepo
                .findById(new TimeEntry.Pk(workspaceId, sourceEntryId))
                .orElseGet(TimeEntry::new);
        entry.setWorkspaceId(workspaceId);
        entry.setSourceEntryId(sourceEntryId);
        entry.setUserId(stringValue(raw.get("userId")));
        // Detailed-report response includes "userName" + "userEmail" per
        // entry (live probe 2026-05-11). Capture the name so findings can
        // render human-readable strings instead of opaque user IDs.
        entry.setUserName(stringValue(raw.get("userName")));
        entry.setProjectId(stringValue(raw.get("projectId")));
        entry.setTaskId(stringValue(raw.get("taskId")));
        entry.setClientId(stringValue(raw.get("clientId")));
        entry.setDescription(stringValue(raw.get("description")));
        entry.setStartAt(parseInstant(raw.get("timeInterval"), "start"));
        entry.setEndAt(parseInstant(raw.get("timeInterval"), "end"));
        entry.setDurationSeconds(parseDurationSeconds(raw.get("timeInterval")));
        entry.setBillable(raw.get("billable") instanceof Boolean b ? b : null);
        entry.setTags(extractTagNames(raw.get("tags")));
        entry.setRaw(raw);
        entry.setIngestedAt(ingestedAt);
        timeEntryRepo.save(entry);
    }

    private IngestionRun newRun(String workspaceId, LocalDate from, LocalDate to) {
        IngestionRun run = new IngestionRun();
        run.setWorkspaceId(workspaceId);
        run.setId(UUID.randomUUID().toString());
        run.setDateRangeStart(from.toString());
        run.setDateRangeEnd(to.toString());
        run.setStatus(IngestionStatus.RUNNING);
        run.setEntriesProcessed(0);
        Instant now = Instant.now();
        run.setCreatedAt(now);
        // completed_at is NOT NULL in the schema; we set the same value as
        // createdAt on the in-flight row and overwrite it in finalize /
        // markRunFailed. Inspecting status==RUNNING is the reliable signal
        // for "not yet finalized".
        run.setCompletedAt(now);
        return run;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringValue(Object... candidates) {
        for (Object c : candidates) {
            if (c instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Instant parseInstant(Object timeInterval, String key) {
        if (!(timeInterval instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = ((Map<String, Object>) map).get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Long parseDurationSeconds(Object timeInterval) {
        if (!(timeInterval instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = ((Map<String, Object>) map).get("duration");
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                // Clockify sometimes returns ISO-8601 duration ("PT1H30M"); we can't parse easily.
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractTagNames(Object tagsField) {
        if (!(tagsField instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new java.util.ArrayList<>();
        for (Object t : list) {
            if (t instanceof Map<?, ?> map) {
                Object name = ((Map<String, Object>) map).get("name");
                if (name instanceof String s) {
                    names.add(s);
                }
            } else if (t instanceof String s) {
                names.add(s);
            }
        }
        return names;
    }

    private record PreparedRun(String runId, String token, String reportsUrl) {}
}
