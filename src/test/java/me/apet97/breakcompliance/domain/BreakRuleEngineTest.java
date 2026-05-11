package me.apet97.breakcompliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.entities.TargetType;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import org.junit.jupiter.api.Test;

class BreakRuleEngineTest {

    private static final String WS = "ws-test";
    private static final String USER = "user-1";

    private final BreakRuleEngine engine = new BreakRuleEngine();

    @Test
    void noTemplateAssigned_emitsAdvisoryFinding() {
        WorkspaceSettings settings = settings(null, false);
        List<TimeEntry> entries = List.of(workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, List.of(), List.of(), entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).hasSize(1);
        FindingDraft f = findings.get(0);
        assertThat(f.code()).isEqualTo(FindingCode.NO_TEMPLATE_ASSIGNED);
        assertThat(f.severity()).isEqualTo(Severity.INFO);
        assertThat(f.userId()).isEqualTo(USER);
    }

    @Test
    void workOverThreshold_noBreak_emitsMissingRequiredBreak() {
        RuleTemplate tpl = template("tpl-1", 240, 15, 240, 5); // work>240 needs 15min break, max-continuous 240, grace 5
        WorkspaceSettings settings = settings(tpl.getId(), false);
        List<TemplateAssignment> assigns = List.of(userAssignment(USER, tpl.getId()));
        List<TimeEntry> entries = List.of(workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z")); // 480 min work

        List<FindingDraft> findings = engine.evaluate(
                input(settings, List.of(tpl), assigns, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK, FindingCode.MAX_CONTINUOUS_WORK_EXCEEDED);
    }

    @Test
    void workOverThreshold_shortBreak_emitsInsufficientBreakDuration() {
        // 15-min minimum break segment; work threshold 240, required 30 — caller takes 20-min break (qualifies, but insufficient).
        RuleTemplate tpl = template("tpl-1", 240, 30, 240, 5);
        tpl.setMinimumValidBreakSegmentMinutes(15);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"), // 240 min work
                breakEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T13:20:00Z"), // 20 min break (qualifies, below 30)
                workEntry("e3", "2026-05-10T13:20:00Z", "2026-05-10T15:00:00Z")); // 100 min work

        List<FindingDraft> findings = engine.evaluate(input(
                settings, List.of(tpl), List.of(userAssignment(USER, tpl.getId())), entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code).contains(FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void workOverThreshold_qualifyingBreak_noViolation() {
        RuleTemplate tpl = template("tpl-1", 240, 30, 240, 5);
        tpl.setMinimumValidBreakSegmentMinutes(15);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"), // 240 min
                breakEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T13:30:00Z"), // 30 min qualifying break
                workEntry("e3", "2026-05-10T13:30:00Z", "2026-05-10T15:00:00Z")); // 90 min

        List<FindingDraft> findings = engine.evaluate(input(
                settings, List.of(tpl), List.of(userAssignment(USER, tpl.getId())), entries, "2026-05-10", "2026-05-10"));

        // No MISSING_REQUIRED_BREAK / INSUFFICIENT_BREAK_DURATION
        assertThat(findings).extracting(FindingDraft::code).doesNotContain(
                FindingCode.MISSING_REQUIRED_BREAK, FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void disabledTemplate_skipsEvaluation() {
        RuleTemplate tpl = template("tpl-1", 240, 30, 240, 5);
        tpl.setEnabled(false);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        List<TimeEntry> entries = List.of(workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(
                settings, List.of(tpl), List.of(userAssignment(USER, tpl.getId())), entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).isEmpty();
    }

    @Test
    void runningEntry_skipped() {
        RuleTemplate tpl = template("tpl-1", 240, 30, 240, 5);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        TimeEntry running = workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z");
        running.setEndAt(null); // running
        List<TimeEntry> entries = List.of(running);

        List<FindingDraft> findings = engine.evaluate(input(
                settings, List.of(tpl), List.of(userAssignment(USER, tpl.getId())), entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).isEmpty();
    }

    @Test
    void customPolicyEnabled_overridesTemplateThresholds() {
        // Template says 480-min threshold (8h) — work of 300 min would not violate.
        RuleTemplate tpl = template("tpl-1", 480, 30, 480, 5);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        settings.setCustomPolicyEnabled(true);
        settings.setCustomWorkThresholdMinutes(120); // tighter custom: 2h
        settings.setCustomBreakThresholdMinutes(30);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T14:00:00Z")); // 300 min

        List<FindingDraft> findings = engine.evaluate(input(
                settings, List.of(tpl), List.of(userAssignment(USER, tpl.getId())),
                entries, "2026-05-10", "2026-05-10"));

        // 300 min > 120 (custom) + 5 (grace) → MISSING_REQUIRED_BREAK
        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
        FindingDraft missingBreak = findings.stream()
                .filter(f -> f.code() == FindingCode.MISSING_REQUIRED_BREAK)
                .findFirst().orElseThrow();
        assertThat(missingBreak.message()).contains("threshold 120");
    }

    @Test
    void customPolicyDisabled_fallsBackToTemplate() {
        // Custom thresholds present but customPolicyEnabled=false → template wins.
        RuleTemplate tpl = template("tpl-1", 480, 30, 480, 5);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        settings.setCustomPolicyEnabled(false);
        settings.setCustomWorkThresholdMinutes(120);
        settings.setCustomBreakThresholdMinutes(30);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T14:00:00Z")); // 300 min

        List<FindingDraft> findings = engine.evaluate(input(
                settings, List.of(tpl), List.of(userAssignment(USER, tpl.getId())),
                entries, "2026-05-10", "2026-05-10"));

        // 300 min < 480 (template) → no MISSING/INSUFFICIENT findings
        assertThat(findings).extracting(FindingDraft::code)
                .doesNotContain(FindingCode.MISSING_REQUIRED_BREAK, FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void customPolicyEnabled_butMissingValues_fallsBackToTemplate() {
        RuleTemplate tpl = template("tpl-1", 480, 30, 480, 5);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        settings.setCustomPolicyEnabled(true);
        // both thresholds null → cannot apply override, template wins

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T14:00:00Z")); // 300 min

        List<FindingDraft> findings = engine.evaluate(input(
                settings, List.of(tpl), List.of(userAssignment(USER, tpl.getId())),
                entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .doesNotContain(FindingCode.MISSING_REQUIRED_BREAK, FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void outputIsDeterministic_sortedByDateUserCode() {
        RuleTemplate tpl = template("tpl-1", 30, 15, 30, 0);
        WorkspaceSettings settings = settings(tpl.getId(), false);
        List<TemplateAssignment> assigns = List.of(
                userAssignment("user-a", tpl.getId()),
                userAssignment("user-b", tpl.getId()));
        List<TimeEntry> entries = List.of(
                workEntryFor("user-a", "ea", "2026-05-11T09:00:00Z", "2026-05-11T10:00:00Z"),
                workEntryFor("user-b", "eb", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(
                input(settings, List.of(tpl), assigns, entries, "2026-05-10", "2026-05-11"));

        // user-b on 5/10 comes before user-a on 5/11
        assertThat(findings).extracting(FindingDraft::date).containsSequence(
                LocalDate.parse("2026-05-10"), LocalDate.parse("2026-05-11"));
    }

    // helpers

    private static WorkspaceSettings settings(String defaultTemplateId, boolean fallback) {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(WS);
        s.setDefaultTemplateId(defaultTemplateId);
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(fallback);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return s;
    }

    private static RuleTemplate template(String id, int workThreshold, int requiredBreak, int maxContinuous, int grace) {
        RuleTemplate t = new RuleTemplate();
        t.setWorkspaceId(WS);
        t.setId(id);
        t.setKey("custom-basic");
        t.setName("Test");
        t.setType(RuleTemplateType.CUSTOM);
        t.setEnabled(true);
        t.setMinimumValidBreakSegmentMinutes(5);
        t.setWorkThresholdMinutes(workThreshold);
        t.setRequiredBreakMinutes(requiredBreak);
        t.setMaxContinuousWorkMinutesBeforeBreak(maxContinuous);
        t.setGracePeriodMinutes(grace);
        t.setAllowSplitBreaks(true);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return t;
    }

    private static TemplateAssignment userAssignment(String userId, String templateId) {
        TemplateAssignment a = new TemplateAssignment();
        a.setWorkspaceId(WS);
        a.setTargetType(TargetType.USER);
        a.setTargetId(userId);
        a.setTemplateId(templateId);
        a.setId("a-" + userId);
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return a;
    }

    private static TimeEntry workEntry(String sourceId, String startIso, String endIso) {
        return workEntryFor(USER, sourceId, startIso, endIso);
    }

    private static TimeEntry workEntryFor(String userId, String sourceId, String startIso, String endIso) {
        TimeEntry e = baseEntry(userId, sourceId, startIso, endIso);
        e.setRaw(Map.of("type", "REGULAR"));
        return e;
    }

    private static TimeEntry breakEntry(String sourceId, String startIso, String endIso) {
        TimeEntry e = baseEntry(USER, sourceId, startIso, endIso);
        e.setRaw(Map.of("type", "BREAK"));
        return e;
    }

    private static TimeEntry baseEntry(String userId, String sourceId, String startIso, String endIso) {
        TimeEntry e = new TimeEntry();
        e.setWorkspaceId(WS);
        e.setSourceEntryId(sourceId);
        e.setUserId(userId);
        Instant start = Instant.parse(startIso);
        Instant end = Instant.parse(endIso);
        e.setStartAt(start);
        e.setEndAt(end);
        e.setDurationSeconds(java.time.Duration.between(start, end).toSeconds());
        e.setTags(new ArrayList<>());
        e.setIngestedAt(Instant.now());
        return e;
    }

    private static BreakRuleEngineInput input(
            WorkspaceSettings settings,
            List<RuleTemplate> templates,
            List<TemplateAssignment> assignments,
            List<TimeEntry> entries,
            String fromIso,
            String toIso) {
        return new BreakRuleEngineInput(
                WS,
                settings,
                templates,
                assignments,
                entries,
                List.of(),
                LocalDate.parse(fromIso),
                LocalDate.parse(toIso));
    }
}
