package me.apet97.breakcompliance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.entities.TargetType;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.TemplateAssignmentRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Multi-tenant isolation guard: two workspaces sharing identical entity ids
 * must not see each other's data, and every workspace-scoped finder must
 * filter on workspace_id.
 *
 * <p>The schema already enforces this structurally — every tenant-scoped
 * table has {@code workspace_id} as the leading column of its composite PK
 * — but this test fails the build the day someone accidentally removes
 * {@code workspace_id} from a finder.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfig.class)
class CrossWorkspaceIsolationTest {

    @Autowired
    WorkspaceSettingsRepository workspaceSettingsRepo;

    @Autowired
    RuleTemplateRepository ruleTemplateRepo;

    @Autowired
    TemplateAssignmentRepository templateAssignmentRepo;

    @Autowired
    TimeEntryRepository timeEntryRepo;

    @Autowired
    FindingRepository findingRepo;

    @Autowired
    WorkspaceHolidayRepository holidayRepo;

    @Test
    void workspaceSettings_areIsolated() {
        workspaceSettingsRepo.saveAndFlush(newSettings("ws-a"));
        workspaceSettingsRepo.saveAndFlush(newSettings("ws-b"));

        WorkspaceSettings a = workspaceSettingsRepo.findById("ws-a").orElseThrow();
        WorkspaceSettings b = workspaceSettingsRepo.findById("ws-b").orElseThrow();

        assertThat(a.getWorkspaceId()).isEqualTo("ws-a");
        assertThat(b.getWorkspaceId()).isEqualTo("ws-b");
    }

    @Test
    void ruleTemplates_sameIdDifferentWorkspace_doNotCollide() {
        ruleTemplateRepo.saveAndFlush(newTemplate("ws-a", "tpl-1"));
        ruleTemplateRepo.saveAndFlush(newTemplate("ws-b", "tpl-1"));

        List<RuleTemplate> a = ruleTemplateRepo.findByWorkspaceId("ws-a");
        List<RuleTemplate> b = ruleTemplateRepo.findByWorkspaceId("ws-b");

        assertThat(a).extracting(RuleTemplate::getWorkspaceId).containsExactly("ws-a");
        assertThat(b).extracting(RuleTemplate::getWorkspaceId).containsExactly("ws-b");
    }

    @Test
    void templateAssignments_sameTargetDifferentWorkspace_doNotCollide() {
        templateAssignmentRepo.saveAndFlush(newAssignment("ws-a", "user-1"));
        templateAssignmentRepo.saveAndFlush(newAssignment("ws-b", "user-1"));

        List<TemplateAssignment> a = templateAssignmentRepo.findByWorkspaceId("ws-a");
        List<TemplateAssignment> b = templateAssignmentRepo.findByWorkspaceId("ws-b");

        assertThat(a).extracting(TemplateAssignment::getWorkspaceId).containsExactly("ws-a");
        assertThat(b).extracting(TemplateAssignment::getWorkspaceId).containsExactly("ws-b");
    }

    @Test
    void timeEntries_sameSourceEntryIdDifferentWorkspace_doNotCollide() {
        timeEntryRepo.saveAndFlush(newTimeEntry("ws-a", "entry-1"));
        timeEntryRepo.saveAndFlush(newTimeEntry("ws-b", "entry-1"));

        List<TimeEntry> a = timeEntryRepo.findByWorkspaceIdAndUserId("ws-a", "user-1");
        List<TimeEntry> b = timeEntryRepo.findByWorkspaceIdAndUserId("ws-b", "user-1");

        assertThat(a).extracting(TimeEntry::getWorkspaceId).containsExactly("ws-a");
        assertThat(b).extracting(TimeEntry::getWorkspaceId).containsExactly("ws-b");
    }

    @Test
    void findings_dateRangeQuery_doesNotLeakAcrossWorkspaces() {
        findingRepo.saveAndFlush(newFinding("ws-a", "f-1"));
        findingRepo.saveAndFlush(newFinding("ws-b", "f-1"));

        List<Finding> a = findingRepo.findByWorkspaceIdAndDateBetween(
                "ws-a", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        List<Finding> b = findingRepo.findByWorkspaceIdAndDateBetween(
                "ws-b", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        assertThat(a).extracting(Finding::getWorkspaceId).containsExactly("ws-a");
        assertThat(b).extracting(Finding::getWorkspaceId).containsExactly("ws-b");
    }

    @Test
    void findings_deleteByDateRange_doesNotTouchOtherWorkspaces() {
        findingRepo.saveAndFlush(newFinding("ws-a", "f-a"));
        findingRepo.saveAndFlush(newFinding("ws-b", "f-b"));

        long deleted = findingRepo.deleteByWorkspaceIdAndDateBetween(
                "ws-a", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(deleted).isEqualTo(1);

        List<Finding> b = findingRepo.findByWorkspaceIdAndDateBetween(
                "ws-b", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(b).hasSize(1);
    }

    @Test
    void workspaceWideHoliday_withNullAppliesToUserId_roundTrips() {
        WorkspaceHoliday workspaceWide = newHoliday("ws-a", "holiday-1", null);
        WorkspaceHoliday userScoped = newHoliday("ws-a", "holiday-1", "user-1");

        holidayRepo.saveAndFlush(workspaceWide);
        holidayRepo.saveAndFlush(userScoped);

        List<WorkspaceHoliday> holidays = holidayRepo.findByWorkspaceIdAndDateBetween(
                "ws-a", LocalDate.parse("2026-05-10"), LocalDate.parse("2026-05-10"));

        assertThat(holidays).hasSize(2);
        assertThat(holidays).extracting(WorkspaceHoliday::getAppliesToUserId)
                .containsExactlyInAnyOrder(null, "user-1");
    }

    private WorkspaceSettings newSettings(String workspaceId) {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(workspaceId);
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return s;
    }

    private RuleTemplate newTemplate(String workspaceId, String id) {
        RuleTemplate t = new RuleTemplate();
        t.setWorkspaceId(workspaceId);
        t.setId(id);
        t.setKey("custom-basic");
        t.setName("Custom");
        t.setType(RuleTemplateType.CUSTOM);
        t.setMinimumValidBreakSegmentMinutes(5);
        t.setWorkThresholdMinutes(240);
        t.setRequiredBreakMinutes(15);
        t.setMaxContinuousWorkMinutesBeforeBreak(240);
        t.setGracePeriodMinutes(5);
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    private TemplateAssignment newAssignment(String workspaceId, String targetId) {
        TemplateAssignment a = new TemplateAssignment();
        a.setWorkspaceId(workspaceId);
        a.setTargetType(TargetType.USER);
        a.setTargetId(targetId);
        a.setTemplateId("tpl-1");
        a.setId("assign-" + workspaceId + "-" + targetId);
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return a;
    }

    private TimeEntry newTimeEntry(String workspaceId, String sourceEntryId) {
        TimeEntry e = new TimeEntry();
        e.setWorkspaceId(workspaceId);
        e.setSourceEntryId(sourceEntryId);
        e.setUserId("user-1");
        e.setStartAt(Instant.parse("2026-05-10T09:00:00Z"));
        e.setRaw(Map.of("seed", true));
        e.setIngestedAt(Instant.now());
        return e;
    }

    private Finding newFinding(String workspaceId, String id) {
        Finding f = new Finding();
        f.setWorkspaceId(workspaceId);
        f.setId(id);
        f.setUserId("user-1");
        f.setDate(LocalDate.parse("2026-05-10"));
        f.setTemplateId("tpl-1");
        f.setSeverity(Severity.WARNING);
        f.setCode(FindingCode.MISSING_REQUIRED_BREAK);
        f.setMessage("isolated finding");
        f.setEvidence(Map.of("seed", true));
        f.setCreatedAt(Instant.now());
        return f;
    }

    private WorkspaceHoliday newHoliday(String workspaceId, String sourceId, String appliesToUserId) {
        WorkspaceHoliday h = new WorkspaceHoliday();
        h.setWorkspaceId(workspaceId);
        h.setSourceId(sourceId);
        h.setDate(LocalDate.parse("2026-05-10"));
        h.setAppliesToUserId(appliesToUserId);
        h.setName("Founders Day");
        h.setIngestedAt(Instant.now());
        return h;
    }
}
