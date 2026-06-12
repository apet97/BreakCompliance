package me.apet97.breakcompliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.clockify.HolidayFetcher;
import me.apet97.breakcompliance.clockify.TimeOffFetcher;
import me.apet97.breakcompliance.clockify.UserDirectoryFetcher;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import me.apet97.breakcompliance.persistence.entities.WorkspaceTimeOff;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceTimeOffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SuppressionCacheRefresher {

    private static final Logger log = LoggerFactory.getLogger(SuppressionCacheRefresher.class);

    private final InstallationRepository installationRepo;
    private final HolidayFetcher holidayFetcher;
    private final TimeOffFetcher timeOffFetcher;
    private final WorkspaceHolidayRepository holidayRepo;
    private final WorkspaceTimeOffRepository timeOffRepo;
    private final UserDirectoryFetcher userDirectoryFetcher;
    private final TimeEntryRepository timeEntryRepo;
    private final TransactionTemplate tx;

    SuppressionCacheRefresher(
            InstallationRepository installationRepo,
            HolidayFetcher holidayFetcher,
            TimeOffFetcher timeOffFetcher,
            WorkspaceHolidayRepository holidayRepo,
            WorkspaceTimeOffRepository timeOffRepo,
            UserDirectoryFetcher userDirectoryFetcher,
            TimeEntryRepository timeEntryRepo,
            PlatformTransactionManager txManager) {
        this.installationRepo = installationRepo;
        this.holidayFetcher = holidayFetcher;
        this.timeOffFetcher = timeOffFetcher;
        this.holidayRepo = holidayRepo;
        this.timeOffRepo = timeOffRepo;
        this.userDirectoryFetcher = userDirectoryFetcher;
        this.timeEntryRepo = timeEntryRepo;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Fetch + upsert holidays + approved time-off for {@code [from, to]}.
     * Idempotent: each refreshed window is delete-then-inserted.
     */
    void refresh(String workspaceId, String token, LocalDate from, LocalDate to) {
        Installation install = installationRepo.findByWorkspaceId(workspaceId).orElse(null);
        if (install == null || install.getBackendUrl() == null || install.getBackendUrl().isBlank()) {
            return;
        }
        String backendUrl = install.getBackendUrl();
        Instant now = Instant.now();

        refreshHolidays(workspaceId, backendUrl, token, from, to, now);
        refreshTimeOff(workspaceId, backendUrl, token, from, to, now);
        reconcileUserDirectory(workspaceId, backendUrl, token);
    }

    private void refreshHolidays(
            String workspaceId, String backendUrl, String token, LocalDate from, LocalDate to, Instant now) {
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
    }

    private void refreshTimeOff(
            String workspaceId, String backendUrl, String token, LocalDate from, LocalDate to, Instant now) {
        Instant windowStart = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
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
    }

    private void reconcileUserDirectory(String workspaceId, String backendUrl, String token) {
        try {
            Map<String, String> directory = userDirectoryFetcher.fetchActive(workspaceId, backendUrl, token);
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
}
