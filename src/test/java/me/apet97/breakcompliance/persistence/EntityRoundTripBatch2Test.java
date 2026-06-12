package me.apet97.breakcompliance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.persistence.entities.AuditLog;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.repositories.AuditLogRepository;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfig.class)
class EntityRoundTripBatch2Test {

    @Autowired
    TimeEntryRepository timeEntryRepo;

    @Autowired
    FindingRepository findingRepo;

    @Autowired
    AuditLogRepository auditLogRepo;

    @Test
    void timeEntry_roundTrip_withJsonbTagsAndRaw() {
        TimeEntry e = new TimeEntry();
        e.setWorkspaceId("ws-1");
        e.setSourceEntryId("entry-1");
        e.setUserId("user-1");
        e.setProjectId("proj-1");
        e.setDescription("worked on feature X");
        e.setStartAt(Instant.parse("2026-05-10T09:00:00Z"));
        e.setEndAt(Instant.parse("2026-05-10T12:00:00Z"));
        e.setDurationSeconds(10_800L);
        e.setBillable(true);
        e.setTags(List.of("project-x", "client-acme"));
        e.setRaw(Map.of("source", "detailed-report", "rawId", "abc"));
        e.setIngestedAt(Instant.now());
        timeEntryRepo.saveAndFlush(e);

        TimeEntry loaded = timeEntryRepo.findById(new TimeEntry.Pk("ws-1", "entry-1")).orElseThrow();
        assertThat(loaded.getTags()).containsExactly("project-x", "client-acme");
        assertThat(loaded.getRaw()).containsEntry("source", "detailed-report");
        assertThat(loaded.getBillable()).isTrue();
        assertThat(loaded.getDurationSeconds()).isEqualTo(10_800L);
    }

    @Test
    void timeEntry_dateRangeAndUserFinders() {
        seedEntry("ws-2", "e-1", "u-1", Instant.parse("2026-05-01T09:00:00Z"));
        seedEntry("ws-2", "e-2", "u-1", Instant.parse("2026-05-03T09:00:00Z"));
        seedEntry("ws-2", "e-3", "u-2", Instant.parse("2026-05-05T09:00:00Z"));

        List<TimeEntry> rangeForU1 = timeEntryRepo.findByWorkspaceIdAndStartAtBetween(
                "ws-2", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-04T00:00:00Z"));
        assertThat(rangeForU1).hasSize(2);

        List<TimeEntry> u1 = timeEntryRepo.findByWorkspaceIdAndUserId("ws-2", "u-1");
        assertThat(u1).hasSize(2);
    }

    @Test
    void finding_roundTrip_withDateAndJsonbEvidence() {
        Finding f = new Finding();
        f.setWorkspaceId("ws-3");
        f.setId("finding-1");
        f.setUserId("user-1");
        f.setDate(LocalDate.parse("2026-05-10"));
        f.setTemplateId("tpl-1");
        f.setSeverity(Severity.VIOLATION);
        f.setCode(FindingCode.INSUFFICIENT_BREAK_DURATION);
        f.setMessage("break too short");
        f.setEvidence(Map.of(
                "workMinutes", 360,
                "breakMinutes", 15,
                "requiredBreakMinutes", 30,
                "entryIds", List.of("e1", "e2")));
        f.setCreatedAt(Instant.now());
        findingRepo.saveAndFlush(f);

        Finding loaded = findingRepo.findById(new Finding.Pk("ws-3", "finding-1")).orElseThrow();
        assertThat(loaded.getCode()).isEqualTo(FindingCode.INSUFFICIENT_BREAK_DURATION);
        assertThat(loaded.getSeverity()).isEqualTo(Severity.VIOLATION);
        assertThat(loaded.getEvidence()).containsKeys("workMinutes", "requiredBreakMinutes", "entryIds");

        List<Finding> inRange = findingRepo.findByWorkspaceIdAndDateBetween(
                "ws-3", LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"));
        assertThat(inRange).hasSize(1);
    }

    @Test
    void finding_atomicReplaceByDateRange() {
        seedFinding("ws-4", "f-1", LocalDate.parse("2026-05-10"));
        seedFinding("ws-4", "f-2", LocalDate.parse("2026-05-11"));
        seedFinding("ws-4", "f-3", LocalDate.parse("2026-06-01"));

        long deleted = findingRepo.deleteByWorkspaceIdAndDateBetween(
                "ws-4", LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"));
        assertThat(deleted).isEqualTo(2);

        List<Finding> remaining = findingRepo.findByWorkspaceIdAndDateBetween(
                "ws-4", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(remaining).extracting(Finding::getId).containsExactly("f-3");
    }

    @Test
    void auditLog_roundTrip_withPrePersistedUuidAndJsonbDetails() {
        AuditLog a = new AuditLog();
        a.setWorkspaceId("ws-5");
        a.setActor("user-1");
        a.setAction("INSTALL");
        a.setDetails(Map.of("addonId", "abc123", "schemaVersion", "1.3"));

        auditLogRepo.saveAndFlush(a);

        assertThat(a.getId()).isNotNull();
        assertThat(a.getCreatedAt()).isNotNull();

        AuditLog loaded = auditLogRepo.findById(a.getId()).orElseThrow();
        assertThat(loaded.getAction()).isEqualTo("INSTALL");
        assertThat(loaded.getDetails()).containsEntry("addonId", "abc123");
    }

    @Test
    void auditLog_findByWorkspaceIdOrderByCreatedAtDesc() {
        AuditLog older = newAuditLog("ws-6", "INSTALL");
        older.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        auditLogRepo.saveAndFlush(older);

        AuditLog newer = newAuditLog("ws-6", "SETTINGS_UPDATED");
        newer.setCreatedAt(Instant.parse("2026-05-10T00:00:00Z"));
        auditLogRepo.saveAndFlush(newer);

        List<AuditLog> ordered = auditLogRepo.findByWorkspaceIdOrderByCreatedAtDesc("ws-6");
        assertThat(ordered).extracting(AuditLog::getAction).containsExactly("SETTINGS_UPDATED", "INSTALL");
    }

    private void seedEntry(String workspaceId, String entryId, String userId, Instant startAt) {
        TimeEntry e = new TimeEntry();
        e.setWorkspaceId(workspaceId);
        e.setSourceEntryId(entryId);
        e.setUserId(userId);
        e.setStartAt(startAt);
        e.setRaw(Map.of("seed", true));
        e.setIngestedAt(Instant.now());
        timeEntryRepo.saveAndFlush(e);
    }

    private void seedFinding(String workspaceId, String id, LocalDate date) {
        Finding f = new Finding();
        f.setWorkspaceId(workspaceId);
        f.setId(id);
        f.setUserId("user-1");
        f.setDate(date);
        f.setTemplateId("tpl-1");
        f.setSeverity(Severity.WARNING);
        f.setCode(FindingCode.MISSING_REQUIRED_BREAK);
        f.setMessage("seed");
        f.setEvidence(Map.of("seed", true));
        f.setCreatedAt(Instant.now());
        findingRepo.saveAndFlush(f);
    }

    private AuditLog newAuditLog(String workspaceId, String action) {
        AuditLog a = new AuditLog();
        a.setWorkspaceId(workspaceId);
        a.setAction(action);
        return a;
    }
}
