package me.apet97.breakcompliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import me.apet97.breakcompliance.clockify.DetailedReportEntry;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import org.springframework.stereotype.Component;

@Component
class TimeEntryUpserter {

    private final TimeEntryRepository timeEntryRepo;

    TimeEntryUpserter(TimeEntryRepository timeEntryRepo) {
        this.timeEntryRepo = timeEntryRepo;
    }

    int deleteRange(String workspaceId, LocalDate from, LocalDate to) {
        Instant fromInclusive = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return timeEntryRepo.deleteByWorkspaceIdAndStartAtGreaterThanEqualAndStartAtLessThan(
                workspaceId, fromInclusive, toExclusive);
    }

    boolean upsert(String workspaceId, DetailedReportEntry raw, Instant ingestedAt) {
        String sourceEntryId = raw.sourceEntryId();
        if (sourceEntryId == null) {
            return false;
        }
        TimeEntry entry = timeEntryRepo
                .findById(new TimeEntry.Pk(workspaceId, sourceEntryId))
                .orElseGet(TimeEntry::new);
        entry.setWorkspaceId(workspaceId);
        entry.setSourceEntryId(sourceEntryId);
        entry.setUserId(raw.userId());
        entry.setUserName(raw.userName());
        entry.setProjectId(raw.projectId());
        entry.setTaskId(raw.taskId());
        entry.setClientId(raw.clientId());
        entry.setDescription(raw.description());
        entry.setStartAt(raw.startAt());
        entry.setEndAt(raw.endAt());
        entry.setDurationSeconds(raw.durationSeconds());
        entry.setBillable(raw.billable());
        entry.setTags(raw.tags());
        entry.setRaw(raw.raw());
        entry.setIngestedAt(ingestedAt);
        timeEntryRepo.save(entry);
        return true;
    }

    void flush() {
        timeEntryRepo.flush();
    }
}
