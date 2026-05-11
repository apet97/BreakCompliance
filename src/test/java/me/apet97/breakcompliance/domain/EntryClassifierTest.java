package me.apet97.breakcompliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link EntryClassifier}. The engine tests in
 * {@link BreakRuleEngineTest} cover the end-to-end behaviour; these locks
 * isolate the per-entry classification table.
 */
class EntryClassifierTest {

    @Test
    void canonicalBreak_isBreak() {
        TimeEntry e = entry(Map.of("type", "BREAK"), null, List.of());
        assertThat(EntryClassifier.classify(e, false)).isEqualTo(EntryClassifier.Kind.BREAK);
    }

    @Test
    void canonicalRegular_isWork() {
        TimeEntry e = entry(Map.of("type", "REGULAR"), null, List.of());
        assertThat(EntryClassifier.classify(e, false)).isEqualTo(EntryClassifier.Kind.WORK);
    }

    @Test
    void canonicalTimeOff_isIgnored() {
        TimeEntry e = entry(Map.of("type", "TIME_OFF"), null, List.of());
        assertThat(EntryClassifier.classify(e, false)).isEqualTo(EntryClassifier.Kind.IGNORED);
    }

    @Test
    void canonicalHoliday_isIgnored() {
        TimeEntry e = entry(Map.of("type", "HOLIDAY"), null, List.of());
        assertThat(EntryClassifier.classify(e, false)).isEqualTo(EntryClassifier.Kind.IGNORED);
    }

    @Test
    void canonicalType_lowercaseFromClockify_stillIgnored() {
        // Defensive: Clockify's docs show TIME_OFF uppercase but the parser
        // shouldn't depend on case.
        TimeEntry e = entry(Map.of("type", "time_off"), null, List.of());
        assertThat(EntryClassifier.classify(e, false)).isEqualTo(EntryClassifier.Kind.IGNORED);
    }

    @Test
    void noCanonicalType_fallbackDisabled_defaultsToWork() {
        TimeEntry e = entry(Map.of(), "Stand-up", List.of());
        assertThat(EntryClassifier.classify(e, false)).isEqualTo(EntryClassifier.Kind.WORK);
    }

    @Test
    void noCanonicalType_fallbackEnabled_descriptionMarkerMakesItBreak() {
        TimeEntry e = entry(Map.of(), "Lunch break with the team", List.of());
        assertThat(EntryClassifier.classify(e, true)).isEqualTo(EntryClassifier.Kind.BREAK);
    }

    @Test
    void noCanonicalType_fallbackEnabled_unrelatedDescription_isWork() {
        TimeEntry e = entry(Map.of(), "Design review with PM", List.of());
        assertThat(EntryClassifier.classify(e, true)).isEqualTo(EntryClassifier.Kind.WORK);
    }

    @Test
    void noCanonicalType_fallbackEnabled_tagMarkerMakesItBreak() {
        TimeEntry e = entry(Map.of(), "Step away", List.of("pause"));
        assertThat(EntryClassifier.classify(e, true)).isEqualTo(EntryClassifier.Kind.BREAK);
    }

    @Test
    void canonicalNonBreakType_fallbackIgnoredEvenIfDescriptionContainsBreak() {
        // The canonical type wins. Description with "break" must not flip a
        // REGULAR entry into BREAK.
        TimeEntry e = entry(Map.of("type", "REGULAR"), "Quick break to debug bug", List.of());
        assertThat(EntryClassifier.classify(e, true)).isEqualTo(EntryClassifier.Kind.WORK);
    }

    @Test
    void blankCanonicalType_falsBackToWordScan() {
        TimeEntry e = entry(Map.of("type", ""), "Lunch", List.of());
        assertThat(EntryClassifier.classify(e, true)).isEqualTo(EntryClassifier.Kind.BREAK);
    }

    @Test
    void wordBoundary_breakerInsideOtherWord_isNotMatched() {
        // "breakdown" should not match the "break" marker.
        TimeEntry e = entry(Map.of(), "Breakdown of dependency graph", List.of());
        assertThat(EntryClassifier.classify(e, true)).isEqualTo(EntryClassifier.Kind.WORK);
    }

    private static TimeEntry entry(Map<String, Object> raw, String description, List<String> tags) {
        TimeEntry e = new TimeEntry();
        e.setRaw(raw);
        e.setDescription(description);
        e.setTags(tags);
        return e;
    }
}
