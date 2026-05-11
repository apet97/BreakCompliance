package me.apet97.breakcompliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import org.junit.jupiter.api.Test;

/**
 * §18 — the engine evaluates each user-day bucket against a synthetic
 * {@code RuleTemplate} built from {@link WorkspaceSettings}. No per-user
 * template resolution, no preset lookup, no {@code RuleTemplate} table
 * reads. All test inputs go through {@link WorkspaceSettings}.
 */
class BreakRuleEngineTest {

    private static final String WS = "ws-test";
    private static final String USER = "user-1";

    private final BreakRuleEngine engine = new BreakRuleEngine();

    @Test
    void workOverThreshold_noBreak_emitsMissingRequiredBreak() {
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z")); // 480 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void workOverThreshold_shortBreakSegment_doesNotCount() {
        WorkspaceSettings settings = workspaceSettings(240, 30, 5);
        settings.setCustomMinBreakSegmentMinutes(15); // 10-min break won't count

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),  // 240 min
                breakEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T13:10:00Z"), // 10 min — below min segment
                workEntry("e3", "2026-05-10T13:10:00Z", "2026-05-10T15:00:00Z")); // 110 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        // Work 350 > 245; break 0 (10-min segment dropped) → MISSING.
        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void workOverThreshold_qualifyingBreak_noViolation() {
        WorkspaceSettings settings = workspaceSettings(240, 30, 5);
        settings.setCustomMinBreakSegmentMinutes(15);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),  // 240 min
                breakEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T13:30:00Z"), // 30 min qualifying
                workEntry("e3", "2026-05-10T13:30:00Z", "2026-05-10T15:00:00Z")); // 90 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code).doesNotContain(
                FindingCode.MISSING_REQUIRED_BREAK, FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void runningEntry_skipped() {
        WorkspaceSettings settings = workspaceSettings(240, 30, 5);
        TimeEntry running = workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z");
        running.setEndAt(null);

        List<FindingDraft> findings = engine.evaluate(input(settings, List.of(running), "2026-05-10", "2026-05-10"));

        assertThat(findings).isEmpty();
    }

    @Test
    void presetKey_arbzg_evaluatesSecondTier() {
        // Admin loaded ArbZG preset → settings reflect ArbZG's two-tier rule.
        // 9 h work → 45 min required. With 30 min taken, INSUFFICIENT for tier 2.
        WorkspaceSettings settings = workspaceSettings(360, 30, 5);
        settings.setAppliedPresetKey("germany-arbzg-style");
        settings.setCustomMinBreakSegmentMinutes(15);
        settings.setCustomSecondWorkThresholdMinutes(540);
        settings.setCustomSecondBreakThresholdMinutes(45);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T08:00:00Z", "2026-05-10T17:30:00Z"),  // 570 min
                breakEntry("e2", "2026-05-10T17:30:00Z", "2026-05-10T18:00:00Z")); // 30 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.INSUFFICIENT_BREAK_DURATION);
        FindingDraft insufficient = findings.stream()
                .filter(f -> f.code() == FindingCode.INSUFFICIENT_BREAK_DURATION)
                .findFirst().orElseThrow();
        assertThat(insufficient.message()).contains("required 45");
    }

    @Test
    void allowSplitBreaks_off_requiresOneUninterruptedSegment() {
        // California-style meal rule: 30 min uninterrupted, no split.
        WorkspaceSettings settings = workspaceSettings(300, 30, 5);
        settings.setCustomMinBreakSegmentMinutes(10);
        settings.setCustomAllowSplitBreaks(false);

        // Two 15-min breaks summing to 30 — would pass with allowSplit=true,
        // must FAIL with allowSplit=false because no single segment is 30 min.
        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),   // 240 min
                breakEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T13:15:00Z"),  // 15 min
                workEntry("e3", "2026-05-10T13:15:00Z", "2026-05-10T16:00:00Z"),   // 165 min
                breakEntry("e4", "2026-05-10T16:00:00Z", "2026-05-10T16:15:00Z"),  // 15 min
                workEntry("e5", "2026-05-10T16:15:00Z", "2026-05-10T17:00:00Z")); // 45 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void allowSplitBreaks_on_sumsQualifyingSegments() {
        WorkspaceSettings settings = workspaceSettings(300, 30, 5);
        settings.setCustomMinBreakSegmentMinutes(10);
        settings.setCustomAllowSplitBreaks(true);

        // Same payload as the previous test — passes when split is allowed.
        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                breakEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T13:15:00Z"),
                workEntry("e3", "2026-05-10T13:15:00Z", "2026-05-10T16:00:00Z"),
                breakEntry("e4", "2026-05-10T16:00:00Z", "2026-05-10T16:15:00Z"),
                workEntry("e5", "2026-05-10T16:15:00Z", "2026-05-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code).doesNotContain(
                FindingCode.MISSING_REQUIRED_BREAK, FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void maxContinuousWork_exceeded_emitsFinding() {
        // 8 h continuous work, no breaks — exceeds max-continuous = 240.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        settings.setCustomMaxContinuousWorkMinutes(240);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z")); // 480 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MAX_CONTINUOUS_WORK_EXCEEDED);
    }

    @Test
    void emptySettings_fallsBackToCustomBasicDefaults() {
        // No custom fields set — synthesizeWorkspaceTemplate falls back to
        // RuleTemplatePresets.CUSTOM_BASIC. With 480 min work, 0 break, the
        // default work threshold of 240 + grace 5 fires a MISSING.
        WorkspaceSettings settings = new WorkspaceSettings();
        settings.setWorkspaceId(WS);
        settings.setAppliedPresetKey("custom-basic");
        settings.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        settings.setFallbackDetectionEnabled(false);
        settings.setCreatedAt(Instant.now());
        settings.setUpdatedAt(Instant.now());

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void outputIsDeterministic_sortedByDateUserCode() {
        WorkspaceSettings settings = workspaceSettings(30, 15, 0);

        List<TimeEntry> entries = List.of(
                workEntryFor("user-a", "ea", "2026-05-11T09:00:00Z", "2026-05-11T10:00:00Z"),
                workEntryFor("user-b", "eb", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-11"));

        assertThat(findings).extracting(FindingDraft::date).containsSequence(
                LocalDate.parse("2026-05-10"), LocalDate.parse("2026-05-11"));
    }

    @Test
    void noFindingsBelowThreshold_silentPass() {
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        // 100 min work, well below 240 threshold.
        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T10:40:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        // No threshold-related findings at all — work below threshold.
        assertThat(findings).noneMatch(f -> f.severity() == Severity.VIOLATION);
    }

    // helpers

    private static WorkspaceSettings workspaceSettings(int workThreshold, int requiredBreak, int grace) {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(WS);
        s.setAppliedPresetKey("custom-basic");
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(false);
        s.setCustomWorkThresholdMinutes(workThreshold);
        s.setCustomBreakThresholdMinutes(requiredBreak);
        s.setCustomGracePeriodMinutes(grace);
        s.setCustomMaxContinuousWorkMinutes(workThreshold);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return s;
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
            List<TimeEntry> entries,
            String fromIso,
            String toIso) {
        return new BreakRuleEngineInput(
                WS,
                settings,
                List.of(),     // templates unused
                List.of(),     // assignments unused
                entries,
                List.of(),     // group memberships unused
                LocalDate.parse(fromIso),
                LocalDate.parse(toIso));
    }
}
