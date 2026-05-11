package me.apet97.breakcompliance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import me.apet97.breakcompliance.persistence.entities.FindingReview;
import me.apet97.breakcompliance.persistence.entities.GroupMembership;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalSource;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.entities.ReviewStatus;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;
import me.apet97.breakcompliance.persistence.entities.TargetType;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.FindingReviewRepository;
import me.apet97.breakcompliance.persistence.repositories.GroupMembershipRepository;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.TemplateAssignmentRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfig.class)
class EntityRoundTripBatch1Test {

    @Autowired
    WorkspaceSettingsRepository workspaceSettingsRepo;

    @Autowired
    RuleTemplateRepository ruleTemplateRepo;

    @Autowired
    TemplateAssignmentRepository templateAssignmentRepo;

    @Autowired
    IngestionRunRepository ingestionRunRepo;

    @Autowired
    GroupMembershipRepository groupMembershipRepo;

    @Autowired
    FindingReviewRepository findingReviewRepo;

    @Autowired
    RefreshSignalRepository refreshSignalRepo;

    @Test
    void workspaceSettings_roundTrip() {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId("ws-1");
        s.setDefaultTemplateId("tpl-1");
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(true);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        workspaceSettingsRepo.saveAndFlush(s);

        WorkspaceSettings loaded = workspaceSettingsRepo.findById("ws-1").orElseThrow();
        assertThat(loaded.getDefaultTemplateId()).isEqualTo("tpl-1");
        assertThat(loaded.isFallbackDetectionEnabled()).isTrue();
        assertThat(loaded.getTimezoneStrategy()).isEqualTo(TimezoneStrategy.ENTRY_TIMEZONE);
    }

    @Test
    void ruleTemplate_roundTrip_withOptionalSecondThreshold() {
        RuleTemplate t = new RuleTemplate();
        t.setWorkspaceId("ws-2");
        t.setId("tpl-1");
        t.setKey("germany-arbzg-style");
        t.setName("Germany ArbZG");
        t.setDescription("German labour law");
        t.setType(RuleTemplateType.BUILT_IN);
        t.setPresetKey("germany-arbzg-style");
        t.setMinimumValidBreakSegmentMinutes(15);
        t.setWorkThresholdMinutes(360);
        t.setRequiredBreakMinutes(30);
        t.setMaxContinuousWorkMinutesBeforeBreak(360);
        t.setSecondThresholdMinutes(540);
        t.setSecondRequiredBreakMinutes(45);
        t.setGracePeriodMinutes(5);
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        ruleTemplateRepo.saveAndFlush(t);

        RuleTemplate loaded = ruleTemplateRepo.findById(new RuleTemplate.Pk("ws-2", "tpl-1")).orElseThrow();
        assertThat(loaded.getKey()).isEqualTo("germany-arbzg-style");
        assertThat(loaded.getType()).isEqualTo(RuleTemplateType.BUILT_IN);
        assertThat(loaded.getRequiredBreakMinutes()).isEqualTo(30);
        assertThat(loaded.getSecondThresholdMinutes()).isEqualTo(540);
        assertThat(loaded.getSecondRequiredBreakMinutes()).isEqualTo(45);

        assertThat(ruleTemplateRepo.findByWorkspaceId("ws-2")).hasSize(1);
        assertThat(ruleTemplateRepo.findByWorkspaceIdAndKey("ws-2", "germany-arbzg-style")).isPresent();
    }

    @Test
    void templateAssignment_roundTrip_withCompositeKey() {
        TemplateAssignment a = new TemplateAssignment();
        a.setWorkspaceId("ws-3");
        a.setTargetType(TargetType.USER);
        a.setTargetId("user-42");
        a.setTemplateId("tpl-1");
        a.setId("assign-1");
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        templateAssignmentRepo.saveAndFlush(a);

        TemplateAssignment loaded = templateAssignmentRepo
                .findById(new TemplateAssignment.Pk("ws-3", TargetType.USER, "user-42"))
                .orElseThrow();
        assertThat(loaded.getTemplateId()).isEqualTo("tpl-1");
        assertThat(templateAssignmentRepo.findByWorkspaceIdAndTemplateId("ws-3", "tpl-1")).hasSize(1);
    }

    @Test
    void ingestionRun_roundTrip() {
        IngestionRun r = new IngestionRun();
        r.setWorkspaceId("ws-4");
        r.setId("run-1");
        r.setDateRangeStart("2026-05-01");
        r.setDateRangeEnd("2026-05-07");
        r.setStatus(IngestionStatus.COMPLETED);
        r.setEntriesProcessed(42);
        Instant now = Instant.now();
        r.setCreatedAt(now);
        r.setCompletedAt(now);
        ingestionRunRepo.saveAndFlush(r);

        IngestionRun loaded = ingestionRunRepo.findById(new IngestionRun.Pk("ws-4", "run-1")).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(IngestionStatus.COMPLETED);
        assertThat(loaded.getEntriesProcessed()).isEqualTo(42);
    }

    @Test
    void groupMembership_roundTrip_withThreeColumnPk() {
        GroupMembership m = new GroupMembership();
        m.setWorkspaceId("ws-5");
        m.setGroupId("grp-1");
        m.setUserId("user-1");
        m.setIngestedAt(Instant.now());
        groupMembershipRepo.saveAndFlush(m);

        GroupMembership loaded = groupMembershipRepo
                .findById(new GroupMembership.Pk("ws-5", "grp-1", "user-1"))
                .orElseThrow();
        assertThat(loaded.getIngestedAt()).isNotNull();
        assertThat(groupMembershipRepo.findByWorkspaceIdAndUserId("ws-5", "user-1")).hasSize(1);
        assertThat(groupMembershipRepo.findByWorkspaceIdAndGroupId("ws-5", "grp-1")).hasSize(1);
    }

    @Test
    void findingReview_roundTrip_withOptionalNote() {
        FindingReview r = new FindingReview();
        r.setWorkspaceId("ws-6");
        r.setFindingId("finding-1");
        r.setStatus(ReviewStatus.ACKNOWLEDGED);
        r.setNote("looked into it; counted manually");
        r.setUpdatedAt(Instant.now());
        findingReviewRepo.saveAndFlush(r);

        FindingReview loaded = findingReviewRepo
                .findById(new FindingReview.Pk("ws-6", "finding-1"))
                .orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ReviewStatus.ACKNOWLEDGED);
        assertThat(loaded.getNote()).contains("counted manually");
    }

    @Test
    void refreshSignal_roundTrip_withNullableDetailFields() {
        RefreshSignal s = new RefreshSignal();
        s.setWorkspaceId("ws-7");
        s.setId("sig-1");
        s.setSource(RefreshSignalSource.WEBHOOK);
        s.setEventType("NEW_TIME_ENTRY");
        s.setEntityId(null);
        s.setDateHint(null);
        s.setReceivedAt(Instant.now());
        s.setStatus(RefreshSignalStatus.PENDING);
        refreshSignalRepo.saveAndFlush(s);

        RefreshSignal loaded = refreshSignalRepo.findById(new RefreshSignal.Pk("ws-7", "sig-1")).orElseThrow();
        assertThat(loaded.getSource()).isEqualTo(RefreshSignalSource.WEBHOOK);
        assertThat(loaded.getEventType()).isEqualTo("NEW_TIME_ENTRY");
        assertThat(loaded.getEntityId()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(RefreshSignalStatus.PENDING);
    }
}
