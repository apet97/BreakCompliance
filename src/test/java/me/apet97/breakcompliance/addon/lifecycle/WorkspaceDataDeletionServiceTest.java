package me.apet97.breakcompliance.addon.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.entities.AuditLog;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.GroupMembership;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalSource;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.entities.TargetType;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.AuditLogRepository;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.GroupMembershipRepository;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.TemplateAssignmentRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the contract that {@code DELETED} lifecycle wipes ONLY the target
 * workspace's tenant rows. A typo in any of the ten JPQL DELETEs would
 * either leak data into the other workspace or silently miss a table —
 * this test fails the build either way.
 */
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
@Transactional
class WorkspaceDataDeletionServiceTest {

    private static final String WS_A = "ws-deletion-A";
    private static final String WS_B = "ws-deletion-B";

    @Autowired WorkspaceDataDeletionService service;
    @Autowired WorkspaceSettingsRepository settingsRepo;
    @Autowired RuleTemplateRepository templatesRepo;
    @Autowired TemplateAssignmentRepository assignmentsRepo;
    @Autowired GroupMembershipRepository groupsRepo;
    @Autowired TimeEntryRepository timeEntriesRepo;
    @Autowired IngestionRunRepository runsRepo;
    @Autowired FindingRepository findingsRepo;
    @Autowired RefreshSignalRepository signalsRepo;
    @Autowired AuditLogRepository auditRepo;

    @BeforeEach
    void clean() {
        // Spring's @Transactional rolls back per test, but seeding two
        // workspaces every time is cheaper than counting.
        seed(WS_A);
        seed(WS_B);
    }

    @Test
    void deleteWorkspaceData_wipesAllTenantRowsForTargetWorkspace() {
        // Sanity: both workspaces have rows pre-delete.
        assertThat(settingsRepo.existsById(WS_A)).isTrue();
        assertThat(templatesRepo.findByWorkspaceId(WS_A)).isNotEmpty();
        assertThat(assignmentsRepo.findAll()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(groupsRepo.findAll()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(timeEntriesRepo.findAll()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(runsRepo.findAll()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(findingsRepo.findAll()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(signalsRepo.findAll()).hasSizeGreaterThanOrEqualTo(2);

        service.deleteWorkspaceData(WS_A);

        // Target workspace is empty.
        assertThat(settingsRepo.existsById(WS_A)).isFalse();
        assertThat(templatesRepo.findByWorkspaceId(WS_A)).isEmpty();
        assertThat(timeEntriesRepo.findByWorkspaceIdAndStartAtBetween(
                WS_A, Instant.EPOCH, Instant.now().plusSeconds(86400))).isEmpty();
        assertThat(runsRepo.findByWorkspaceIdOrderByCreatedAtDesc(WS_A)).isEmpty();
        assertThat(findingsRepo.findByWorkspaceIdAndDateBetween(
                WS_A, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
        assertThat(signalsRepo.findByWorkspaceIdOrderByReceivedAtDesc(WS_A)).isEmpty();
    }

    @Test
    void deleteWorkspaceData_doesNotTouchOtherWorkspace() {
        service.deleteWorkspaceData(WS_A);

        // WS_B is fully intact.
        assertThat(settingsRepo.existsById(WS_B)).isTrue();
        assertThat(templatesRepo.findByWorkspaceId(WS_B)).isNotEmpty();
        assertThat(timeEntriesRepo.findByWorkspaceIdAndStartAtBetween(
                WS_B, Instant.EPOCH, Instant.now().plusSeconds(86400))).isNotEmpty();
        assertThat(runsRepo.findByWorkspaceIdOrderByCreatedAtDesc(WS_B)).isNotEmpty();
        assertThat(findingsRepo.findByWorkspaceIdAndDateBetween(
                WS_B, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isNotEmpty();
        assertThat(signalsRepo.findByWorkspaceIdOrderByReceivedAtDesc(WS_B)).isNotEmpty();
    }

    @Test
    void deleteWorkspaceData_unknownWorkspaceIsNoOp() {
        long settingsBefore = settingsRepo.count();
        long timeEntriesBefore = timeEntriesRepo.count();

        service.deleteWorkspaceData("ws-does-not-exist");

        assertThat(settingsRepo.count()).isEqualTo(settingsBefore);
        assertThat(timeEntriesRepo.count()).isEqualTo(timeEntriesBefore);
    }

    // ──────────────────────────── seed helpers ────────────────────────────

    private void seed(String ws) {
        Instant now = Instant.now();

        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(ws);
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(false);
        s.setAppliedPresetKey("custom-basic");
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        settingsRepo.save(s);

        RuleTemplate t = new RuleTemplate();
        t.setWorkspaceId(ws);
        t.setId("tmpl-" + ws);
        t.setKey("custom-basic");
        t.setName("Custom basic");
        t.setDescription("test");
        t.setType(RuleTemplateType.BUILT_IN);
        t.setPresetKey("custom-basic");
        t.setVersion(1);
        t.setEnabled(true);
        t.setWorkThresholdMinutes(240);
        t.setRequiredBreakMinutes(15);
        t.setMinimumValidBreakSegmentMinutes(5);
        t.setMaxContinuousWorkMinutesBeforeBreak(240);
        t.setGracePeriodMinutes(5);
        t.setAllowSplitBreaks(true);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        templatesRepo.save(t);

        TemplateAssignment ta = new TemplateAssignment();
        ta.setWorkspaceId(ws);
        ta.setTargetType(TargetType.USER);
        ta.setTargetId("user-" + ws);
        ta.setTemplateId(t.getId());
        ta.setId(UUID.randomUUID().toString());
        ta.setCreatedAt(now);
        ta.setUpdatedAt(now);
        assignmentsRepo.save(ta);

        GroupMembership gm = new GroupMembership();
        gm.setWorkspaceId(ws);
        gm.setGroupId("g-" + ws);
        gm.setUserId("user-" + ws);
        gm.setIngestedAt(now);
        groupsRepo.save(gm);

        TimeEntry e = new TimeEntry();
        e.setWorkspaceId(ws);
        e.setSourceEntryId("te-" + ws);
        e.setUserId("user-" + ws);
        e.setStartAt(now.minusSeconds(3600));
        e.setEndAt(now);
        e.setDurationSeconds(3600L);
        e.setIngestedAt(now);
        e.setRaw(Map.of("type", "REGULAR"));
        timeEntriesRepo.save(e);

        IngestionRun ir = new IngestionRun();
        ir.setWorkspaceId(ws);
        ir.setId(UUID.randomUUID().toString());
        ir.setDateRangeStart("2026-05-01");
        ir.setDateRangeEnd("2026-05-07");
        ir.setStatus(IngestionStatus.COMPLETED);
        ir.setEntriesProcessed(1);
        ir.setCreatedAt(now);
        ir.setCompletedAt(now);
        runsRepo.save(ir);

        Finding f = new Finding();
        f.setWorkspaceId(ws);
        f.setId("f-" + ws);
        f.setUserId("user-" + ws);
        f.setDate(LocalDate.of(2026, 5, 6));
        f.setTemplateId(t.getId());
        f.setSeverity(Severity.VIOLATION);
        f.setCode(FindingCode.MISSING_REQUIRED_BREAK);
        f.setMessage("seed");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("workMinutes", 540);
        f.setEvidence(evidence);
        f.setCreatedAt(now);
        findingsRepo.save(f);

        RefreshSignal sig = new RefreshSignal();
        sig.setWorkspaceId(ws);
        sig.setId("sig-" + ws);
        sig.setSource(RefreshSignalSource.WEBHOOK);
        sig.setEventType("NEW_TIME_ENTRY");
        sig.setReceivedAt(now);
        sig.setStatus(RefreshSignalStatus.PENDING);
        signalsRepo.save(sig);

        AuditLog audit = new AuditLog();
        audit.setWorkspaceId(ws);
        audit.setAction("test.seed");
        audit.setCreatedAt(now);
        auditRepo.save(audit);
    }
}
