package me.apet97.breakcompliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.apet97.breakcompliance.clockify.ClockifyApiException;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final InstallationRepository installationRepo;
    private final TimeEntryRepository timeEntryRepo;
    private final IngestionRunRepository runRepo;
    private final DetailedReportFetcher fetcher;
    private final TokenCodec codec;

    public IngestionService(
            InstallationRepository installationRepo,
            TimeEntryRepository timeEntryRepo,
            IngestionRunRepository runRepo,
            DetailedReportFetcher fetcher,
            TokenCodec codec) {
        this.installationRepo = installationRepo;
        this.timeEntryRepo = timeEntryRepo;
        this.runRepo = runRepo;
        this.fetcher = fetcher;
        this.codec = codec;
    }

    @Transactional
    public IngestionRun ingest(String workspaceId, LocalDate from, LocalDate to) {
        return ingest(workspaceId, from, to, null);
    }

    /**
     * Run an ingestion using a per-request {@code reportsUrl} (typically read
     * from the caller's user-token JWT claims). The dev-portal lifecycle JWT
     * does not include {@code reportsUrl}, so the persisted installation row
     * may be missing it; the user-token JWT always carries it per the
     * environments-and-regions spec. When the override is present and the
     * install row was missing the value, the row is backfilled so later
     * background jobs (without a user request) can still call the reports
     * API for this workspace.
     */
    @Transactional
    public IngestionRun ingest(String workspaceId, LocalDate from, LocalDate to, String reportsUrlOverride) {
        Installation install = installationRepo
                .findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalStateException(
                        "no installation for workspaceId=" + workspaceId + "; install the addon first"));
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
        runRepo.saveAndFlush(run);

        try {
            List<Map<String, Object>> entries = fetcher.fetch(workspaceId, reportsUrl, token, from, to);
            int processed = 0;
            Instant ingestedAt = Instant.now();
            for (Map<String, Object> raw : entries) {
                upsertEntry(workspaceId, raw, ingestedAt);
                processed++;
            }
            run.setStatus(IngestionStatus.COMPLETED);
            run.setEntriesProcessed(processed);
            run.setCompletedAt(Instant.now());
            log.info("ingestion.completed workspace={} entries={}", workspaceId, processed);
        } catch (ClockifyApiException e) {
            run.setStatus(IngestionStatus.FAILED);
            run.setErrorCode("ClockifyApi:" + e.statusCode());
            run.setCompletedAt(Instant.now());
            runRepo.saveAndFlush(run);
            log.warn("ingestion.failed.clockify workspace={} status={}", workspaceId, e.statusCode());
            // Propagate so the controller can map status-specific responses
            // (notably 401 → user-friendly 503 reports_unavailable).
            throw e;
        } catch (Exception e) {
            run.setStatus(IngestionStatus.FAILED);
            run.setErrorCode(e.getClass().getSimpleName());
            run.setCompletedAt(Instant.now());
            log.warn("ingestion.failed workspace={} reason={}", workspaceId, e.getClass().getSimpleName(), e);
        }
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
        run.setStatus(IngestionStatus.COMPLETED);
        run.setEntriesProcessed(0);
        Instant now = Instant.now();
        run.setCreatedAt(now);
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
}
