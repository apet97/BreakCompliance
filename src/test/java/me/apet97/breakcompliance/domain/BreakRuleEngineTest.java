package me.apet97.breakcompliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void timeOffEntry_excludedFromWorkMinutes_noBreakViolation() {
        // A full-day PTO entry must NOT count toward the daily work-threshold
        // calculation. Before the fix the canonical TIME_OFF type was
        // classified as WORK (anything-not-BREAK), which produced
        // false-positive MISSING_REQUIRED_BREAK findings on PTO days.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);

        List<TimeEntry> entries = List.of(
                timeOffEntry("e1", "2026-05-10T08:00:00Z", "2026-05-10T16:00:00Z")); // 480 min PTO

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).isEmpty();
    }

    @Test
    void holidayEntry_excludedFromWorkMinutes_noBreakViolation() {
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);

        List<TimeEntry> entries = List.of(
                holidayEntry("e1", "2026-05-10T00:00:00Z", "2026-05-10T08:00:00Z")); // 8h HOLIDAY

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).isEmpty();
    }

    @Test
    void timeOffMixedWithWork_onlyWorkMinutesCountToThreshold() {
        // 3h work + 5h PTO on the same day. Without the IGNORED rule the
        // engine would tally 8h and fire a MISSING break finding; with the
        // rule only the 3h work counts and the day passes.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T12:00:00Z"),     // 180 min work
                timeOffEntry("e2", "2026-05-10T12:00:00Z", "2026-05-10T17:00:00Z")); // 300 min PTO

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).isEmpty();
    }

    // ---------- evidence map regression guards ----------

    @Test
    void fallbackOn_qualifyingGap_evidenceCarriesSyntheticMinutes() {
        // 4h work + 30 min gap + 4h work, required break 60 min.
        // The gap qualifies as a 30-min synthesised break (still below
        // required 60) ⇒ INSUFFICIENT_BREAK_DURATION fires AND its
        // evidence map must report syntheticBreakMinutes=30 alongside
        // breakMinutes=30. Without this assertion a refactor could
        // silently drop the new evidence key.
        WorkspaceSettings settings = workspaceSettings(240, 60, 5);
        settings.setFallbackDetectionEnabled(true);
        settings.setCustomMinBreakSegmentMinutes(5);
        settings.setCustomMaxContinuousWorkMinutes(600);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                workEntry("e2", "2026-05-10T13:30:00Z", "2026-05-10T17:30:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        FindingDraft insufficient = findings.stream()
                .filter(f -> f.code() == FindingCode.INSUFFICIENT_BREAK_DURATION)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected INSUFFICIENT_BREAK_DURATION"));
        assertThat(insufficient.evidence()).containsEntry("breakMinutes", 30);
        assertThat(insufficient.evidence()).containsEntry("syntheticBreakMinutes", 30);
    }

    @Test
    void fallbackOff_evidenceShowsZeroSynthetic() {
        // Standard flow with an explicit (sub-floor) BREAK entry, flag OFF.
        // Evidence must carry syntheticBreakMinutes=0 explicitly — the key
        // is always present so the sidebar's `?? 0` fallback is belt-and-
        // braces, not load-bearing.
        WorkspaceSettings settings = workspaceSettings(240, 30, 5);
        settings.setCustomMinBreakSegmentMinutes(15);
        settings.setFallbackDetectionEnabled(false);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                breakEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T13:10:00Z"),
                workEntry("e3", "2026-05-10T13:10:00Z", "2026-05-10T15:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        FindingDraft missing = findings.stream()
                .filter(f -> f.code() == FindingCode.MISSING_REQUIRED_BREAK)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected MISSING_REQUIRED_BREAK"));
        assertThat(missing.evidence()).containsEntry("syntheticBreakMinutes", 0);
    }

    // ---------- fallbackDetectionEnabled (gap-as-break) ----------

    @Test
    void fallbackOn_qualifyingGap_countsAsBreak() {
        // Two 4-hour REGULAR entries separated by a 30-min gap on the same
        // day. Threshold 240, required 15. With the heuristic ON the 30-min
        // gap counts as a qualifying break — no MISSING_REQUIRED_BREAK.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        settings.setFallbackDetectionEnabled(true);
        settings.setCustomMinBreakSegmentMinutes(5);
        settings.setCustomMaxContinuousWorkMinutes(600); // don't fire continuous-work finding

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                workEntry("e2", "2026-05-10T13:30:00Z", "2026-05-10T17:30:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code).doesNotContain(
                FindingCode.MISSING_REQUIRED_BREAK, FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void fallbackOff_qualifyingGap_doesNotCountAsBreak() {
        // Same payload as above, flag OFF. Heuristic must NOT fire — gap is
        // ignored, day fires MISSING_REQUIRED_BREAK (regression guard).
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        settings.setFallbackDetectionEnabled(false);
        settings.setCustomMinBreakSegmentMinutes(5);
        settings.setCustomMaxContinuousWorkMinutes(600);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                workEntry("e2", "2026-05-10T13:30:00Z", "2026-05-10T17:30:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void fallbackOn_gapBelowMinSegment_ignored() {
        // 3-min gap, below default 5-min minBreakSegment. Even with flag ON
        // the gap is too small to qualify, day fails.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        settings.setFallbackDetectionEnabled(true);
        settings.setCustomMinBreakSegmentMinutes(5);
        settings.setCustomMaxContinuousWorkMinutes(600);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                workEntry("e2", "2026-05-10T13:03:00Z", "2026-05-10T17:03:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void fallbackOn_gapAboveCeiling_ignored() {
        // 3-hour gap > 120-min ceiling. The user effectively started a new
        // shift after a long absence — heuristic must NOT credit this.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        settings.setFallbackDetectionEnabled(true);
        settings.setCustomMinBreakSegmentMinutes(5);
        settings.setCustomMaxContinuousWorkMinutes(600);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),  // 240 min
                workEntry("e2", "2026-05-10T16:00:00Z", "2026-05-10T20:00:00Z")); // 240 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void fallbackOn_gapResetsContinuousWorkCounter() {
        // 5h + 30-min gap + 3h = 8h work, but no single run exceeds 5h.
        // maxContinuous = 240; flag ON ⇒ gap resets the run ⇒ no
        // MAX_CONTINUOUS_WORK_EXCEEDED. Flag OFF ⇒ engine sees one 8h run
        // (since intermediate gaps are not real BREAK entries) ⇒ fires.
        WorkspaceSettings on = workspaceSettings(240, 15, 5);
        on.setFallbackDetectionEnabled(true);
        on.setCustomMinBreakSegmentMinutes(5);
        on.setCustomMaxContinuousWorkMinutes(300); // 5h limit

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T14:00:00Z"),  // 5h
                workEntry("e2", "2026-05-10T14:30:00Z", "2026-05-10T17:30:00Z")); // 3h

        List<FindingDraft> findingsOn = engine.evaluate(input(on, entries, "2026-05-10", "2026-05-10"));
        assertThat(findingsOn).extracting(FindingDraft::code)
                .doesNotContain(FindingCode.MAX_CONTINUOUS_WORK_EXCEEDED);

        WorkspaceSettings off = workspaceSettings(240, 15, 5);
        off.setFallbackDetectionEnabled(false);
        off.setCustomMinBreakSegmentMinutes(5);
        off.setCustomMaxContinuousWorkMinutes(300);

        List<FindingDraft> findingsOff = engine.evaluate(input(off, entries, "2026-05-10", "2026-05-10"));
        assertThat(findingsOff).extracting(FindingDraft::code)
                .contains(FindingCode.MAX_CONTINUOUS_WORK_EXCEEDED);
    }

    @Test
    void fallbackOn_californiaRule_longestSegmentWins() {
        // allowSplitBreaks=false. Two below-floor gaps (4 min each, below
        // 10-min floor) plus a real 12-min BREAK. With flag ON the gaps are
        // dropped (below floor), the longest qualifying break is the 12-min
        // BREAK entry, required is 15 ⇒ INSUFFICIENT_BREAK_DURATION.
        WorkspaceSettings settings = workspaceSettings(300, 15, 5);
        settings.setFallbackDetectionEnabled(true);
        settings.setCustomMinBreakSegmentMinutes(10);
        settings.setCustomAllowSplitBreaks(false);
        settings.setCustomMaxContinuousWorkMinutes(600);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T11:00:00Z"),   // 120 min
                workEntry("e2", "2026-05-10T11:04:00Z", "2026-05-10T13:00:00Z"),   // 116 min (4-min gap)
                breakEntry("e3", "2026-05-10T13:00:00Z", "2026-05-10T13:12:00Z"),  // 12 min explicit
                workEntry("e4", "2026-05-10T13:12:00Z", "2026-05-10T15:00:00Z"),   // 108 min
                workEntry("e5", "2026-05-10T15:04:00Z", "2026-05-10T16:00:00Z")); // 56 min (4-min gap)

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.INSUFFICIENT_BREAK_DURATION);
    }

    @Test
    void fallbackOn_timeOffBreaksPrevWorkChain_noSynthesisAcrossPto() {
        // WORK 9-10, TIME_OFF 10-10:30, WORK 10:30-15:00. The 30-min wall
        // gap from end-of-work1 to start-of-work2 would qualify if we
        // naively spanned it. The IGNORED reset ensures we don't credit a
        // break that the user actually spent on PTO ⇒ day still fires
        // MISSING_REQUIRED_BREAK because the only "break" is PTO.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        settings.setFallbackDetectionEnabled(true);
        settings.setCustomMinBreakSegmentMinutes(5);
        settings.setCustomMaxContinuousWorkMinutes(600);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z"),     // 60 min
                timeOffEntry("e2", "2026-05-10T10:00:00Z", "2026-05-10T10:30:00Z"),  // 30 min PTO (IGNORED)
                workEntry("e3", "2026-05-10T10:30:00Z", "2026-05-10T15:00:00Z"));    // 270 min

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void timeOffBetweenWorkEntries_doesNotCreateSyntheticBreakAndSplitsContinuousRun() {
        WorkspaceSettings settings = workspaceSettings(240, 30, 0);
        settings.setFallbackDetectionEnabled(true);
        settings.setCustomMinBreakSegmentMinutes(5);
        settings.setCustomMaxContinuousWorkMinutes(300);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                timeOffEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T14:00:00Z"),
                workEntry("e3", "2026-05-10T14:00:00Z", "2026-05-10T18:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK)
                .doesNotContain(FindingCode.MAX_CONTINUOUS_WORK_EXCEEDED);
        FindingDraft missing = findings.stream()
                .filter(f -> f.code() == FindingCode.MISSING_REQUIRED_BREAK)
                .findFirst()
                .orElseThrow();
        assertThat(missing.evidence()).containsEntry("syntheticBreakMinutes", 0);
    }

    @Test
    void mixedWorkAndTimeOff_stillEvaluatesWorkMinutes() {
        WorkspaceSettings settings = workspaceSettings(240, 15, 0);
        settings.setCustomMaxContinuousWorkMinutes(600);

        List<TimeEntry> entries = List.of(
                workEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T14:00:00Z"),
                timeOffEntry("e2", "2026-05-10T14:00:00Z", "2026-05-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).extracting(FindingDraft::code)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
    }

    @Test
    void pureTimeOffDay_producesNoFindings() {
        WorkspaceSettings settings = workspaceSettings(240, 15, 0);

        List<TimeEntry> entries = List.of(
                timeOffEntry("e1", "2026-05-10T09:00:00Z", "2026-05-10T13:00:00Z"),
                holidayEntry("e2", "2026-05-10T13:00:00Z", "2026-05-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        assertThat(findings).isEmpty();
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

    private static TimeEntry timeOffEntry(String sourceId, String startIso, String endIso) {
        TimeEntry e = baseEntry(USER, sourceId, startIso, endIso);
        e.setRaw(Map.of("type", "TIME_OFF"));
        return e;
    }

    private static TimeEntry holidayEntry(String sourceId, String startIso, String endIso) {
        TimeEntry e = baseEntry(USER, sourceId, startIso, endIso);
        e.setRaw(Map.of("type", "HOLIDAY"));
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
                entries,
                LocalDate.parse(fromIso),
                LocalDate.parse(toIso));
    }

    // ───────────────────────── P1.1 / P1.2 / P6.2 ─────────────────────────

    /**
     * Build an engine input with the P1.1 / P1.2 suppression sets populated.
     * Mirrors what {@code FindingsService} hands in after reading the cached
     * holiday + time-off rows for the date range.
     */
    private static BreakRuleEngineInput inputWithSuppression(
            WorkspaceSettings settings,
            List<TimeEntry> entries,
            String fromIso,
            String toIso,
            Set<LocalDate> workspaceWide,
            Map<String, Set<LocalDate>> perUser) {
        return new BreakRuleEngineInput(
                WS, settings,
                entries, LocalDate.parse(fromIso), LocalDate.parse(toIso),
                workspaceWide, perUser);
    }

    @Test
    void workspaceWideHoliday_suppressesEveryUsersBucket() {
        // Two users, both with a textbook 8h-no-break shift on the same
        // date. Without suppression that's 2 MISSING_REQUIRED_BREAK
        // findings; the workspace-wide holiday must drop both.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        List<TimeEntry> entries = List.of(
                workEntryFor("user-a", "ea", "2026-12-25T09:00:00Z", "2026-12-25T17:00:00Z"),
                workEntryFor("user-b", "eb", "2026-12-25T09:00:00Z", "2026-12-25T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(inputWithSuppression(
                settings, entries, "2026-12-25", "2026-12-25",
                Set.of(LocalDate.parse("2026-12-25")),
                Map.of()));

        assertThat(findings).isEmpty();
    }

    @Test
    void perUserHoliday_suppressesOnlyThatUser() {
        // user-a is on a per-user holiday; user-b worked the same day and
        // should still fire its violation.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        List<TimeEntry> entries = List.of(
                workEntryFor("user-a", "ea", "2026-12-10T09:00:00Z", "2026-12-10T17:00:00Z"),
                workEntryFor("user-b", "eb", "2026-12-10T09:00:00Z", "2026-12-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(inputWithSuppression(
                settings, entries, "2026-12-10", "2026-12-10",
                Set.of(),
                Map.of("user-a", Set.of(LocalDate.parse("2026-12-10")))));

        // user-a is suppressed; every emitted finding belongs to user-b
        // (which may fire both MISSING_REQUIRED_BREAK and
        // MAX_CONTINUOUS_WORK_EXCEEDED for the same 8h-no-break shift).
        assertThat(findings).isNotEmpty();
        assertThat(findings).extracting(FindingDraft::userId).containsOnly("user-b");
    }

    @Test
    void perUserHoliday_suppressesAllRulesIncludingMaxContinuous() {
        // User-specific holiday on this day; the engine should suppress
        // both the missing-break and max-continuous-work rules in one go —
        // suppression happens at bucketing time, before any rule fires.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        List<TimeEntry> entries = List.of(
                workEntryFor("user-a", "ea", "2026-12-10T08:00:00Z", "2026-12-10T18:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(inputWithSuppression(
                settings, entries, "2026-12-10", "2026-12-10",
                Set.of(),
                Map.of("user-a", Set.of(LocalDate.parse("2026-12-10")))));

        assertThat(findings).isEmpty();
    }

    @Test
    void exemptUserSetting_suppressesAcrossEveryDate() {
        // P6.2 — exemptUserIds applies globally, not just on suppressed dates.
        WorkspaceSettings settings = workspaceSettings(240, 15, 5);
        settings.setExemptUserIds("user-a, user-b");

        List<TimeEntry> entries = List.of(
                workEntryFor("user-a", "ea", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z"),
                workEntryFor("user-c", "ec", "2026-05-10T09:00:00Z", "2026-05-10T17:00:00Z"));

        List<FindingDraft> findings = engine.evaluate(input(settings, entries, "2026-05-10", "2026-05-10"));

        // user-a exempt; every emitted finding belongs to user-c.
        assertThat(findings).isNotEmpty();
        assertThat(findings).extracting(FindingDraft::userId).containsOnly("user-c");
    }
}
