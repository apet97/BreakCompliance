package me.apet97.breakcompliance.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import me.apet97.breakcompliance.clockify.ClockifyApiException;
import me.apet97.breakcompliance.clockify.DetailedReportEntry;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.config.AsyncConfig;
import me.apet97.breakcompliance.config.MetricsConfig;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
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
    private final IngestionRunRepository runRepo;
    private final DetailedReportFetcher fetcher;
    private final TokenCodec codec;
    private final TransactionTemplate tx;
    private final TransactionTemplate afterRollbackTx;
    private final Executor ingestExecutor;
    private final MeterRegistry meters;
    private final Timer runDuration;
    private final WorkspaceSettingsRepository workspaceSettings;
    private final TimeEntryUpserter timeEntryUpserter;
    private final SuppressionCacheRefresher suppressionCacheRefresher;

    public IngestionService(
            InstallationRepository installationRepo,
            IngestionRunRepository runRepo,
            DetailedReportFetcher fetcher,
            TokenCodec codec,
            PlatformTransactionManager txManager,
            @Qualifier(AsyncConfig.INGEST_EXECUTOR_BEAN) Executor ingestExecutor,
            MeterRegistry meters,
            WorkspaceSettingsRepository workspaceSettings,
            TimeEntryUpserter timeEntryUpserter,
            SuppressionCacheRefresher suppressionCacheRefresher) {
        this.installationRepo = installationRepo;
        this.runRepo = runRepo;
        this.fetcher = fetcher;
        this.codec = codec;
        this.tx = new TransactionTemplate(txManager);
        this.afterRollbackTx = new TransactionTemplate(txManager);
        this.afterRollbackTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ingestExecutor = ingestExecutor;
        this.meters = meters;
        this.workspaceSettings = workspaceSettings;
        this.timeEntryUpserter = timeEntryUpserter;
        this.suppressionCacheRefresher = suppressionCacheRefresher;
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

        List<DetailedReportEntry> entries;
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

        tx.execute(status -> finalizeRun(workspaceId, prepared.runId(), entries));
        // P1.1 / P1.2 — refresh suppression cache after a successful sync
        // ingest, mirroring the async path. Best-effort.
        try {
            suppressionCacheRefresher.refresh(workspaceId, prepared.token(), from, to);
        } catch (RuntimeException e) {
            log.info("ingestion.suppression.refresh-failed workspace={} reason={}",
                    workspaceId, e.getClass().getSimpleName());
        }
        IngestionRun completed = tx.execute(status -> completeRun(workspaceId, prepared.runId()));
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
        List<DetailedReportEntry> entries;
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
            tx.execute(status -> finalizeRun(workspaceId, runId, entries));
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
            suppressionCacheRefresher.refresh(workspaceId, token, from, to);
        } catch (RuntimeException e) {
            log.info("ingestion.suppression.refresh-failed workspace={} reason={}",
                    workspaceId, e.getClass().getSimpleName());
        }

        try {
            IngestionRun completed = tx.execute(status -> completeRun(workspaceId, runId));
            sample.stop(runDuration);
            if (completed != null) {
                meters.counter(MetricsConfig.INGEST_ENTRIES_PROCESSED).increment(completed.getEntriesProcessed());
            }
        } catch (RuntimeException e) {
            tx.execute(status -> markRunFailed(workspaceId, runId, "Complete:" + e.getClass().getSimpleName()));
            meters.counter(MetricsConfig.INGEST_RUN_FAILED, "reason", "Complete:" + e.getClass().getSimpleName())
                    .increment();
            sample.stop(runDuration);
            log.warn("ingestion.async.failed.complete workspace={} reason={}", workspaceId, e.getClass().getSimpleName(), e);
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
            String workspaceId, String runId, List<DetailedReportEntry> entries) {
        IngestionRun run = runRepo
                .findById(new IngestionRun.Pk(workspaceId, runId))
                .orElseThrow(() -> new IllegalStateException(
                        "ingestion run vanished between prepare and finalize: " + runId));
        int processed = 0;
        int batchCounter = 0;
        Instant ingestedAt = Instant.now();
        for (DetailedReportEntry raw : entries) {
            if (timeEntryUpserter.upsert(workspaceId, raw, ingestedAt)) {
                processed++;
            }
            if (++batchCounter >= FINALIZE_FLUSH_BATCH) {
                // Periodic flush keeps the persistence context's first-level
                // cache bounded — a 100k-entry ingest would otherwise hold
                // every TimeEntry in memory until commit.
                timeEntryUpserter.flush();
                batchCounter = 0;
            }
        }
        run.setEntriesProcessed(processed);
        return runRepo.saveAndFlush(run);
    }

    private IngestionRun completeRun(String workspaceId, String runId) {
        IngestionRun run = runRepo
                .findById(new IngestionRun.Pk(workspaceId, runId))
                .orElseThrow(() -> new IllegalStateException(
                        "ingestion run vanished before completion marker: " + runId));
        run.setStatus(IngestionStatus.COMPLETED);
        run.setCompletedAt(Instant.now());
        log.info("ingestion.completed workspace={} entries={}", workspaceId, run.getEntriesProcessed());
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

    private record PreparedRun(String runId, String token, String reportsUrl) {}
}
