package me.apet97.breakcompliance.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;

/**
 * Classifies an ingested time entry as {@code BREAK}, {@code WORK}, or
 * {@code IGNORED}.
 *
 * <p>Canonical Detailed Report {@code type} wins:
 * <ul>
 *   <li>{@code BREAK} → {@link Kind#BREAK}: counts toward qualifying-break minutes.</li>
 *   <li>{@code TIME_OFF}, {@code HOLIDAY} → {@link Kind#IGNORED}: the entry counts
 *       as neither work nor break; the user wasn't working during these
 *       minutes, so work-threshold and continuous-work calculations exclude
 *       this duration.
 *       (Before this rule the engine misclassified PTO/holiday days as work
 *       and reported false-positive missing-break findings.)</li>
 *   <li>Any other explicit non-empty type (e.g. {@code REGULAR}) → {@link Kind#WORK}.</li>
 * </ul>
 *
 * <p>When the workspace has {@code fallback_detection_enabled = true} AND the
 * canonical type is missing/blank, we do a conservative word-boundary marker
 * scan over {@code description} + {@code tags}. The heuristic is advisory
 * and never overrides an explicit canonical type — this matches the TS
 * {@code classifyIngestedEntryForBreakDetection} contract.
 */
public final class EntryClassifier {

    public enum Kind {
        BREAK,
        WORK,
        /** PTO / holiday entries — count as neither work nor break. */
        IGNORED
    }

    private static final Set<String> MARKERS = Set.of("break", "pause", "lunch", "rest");
    /**
     * Canonical Detailed Report {@code type} values that mean "the user was
     * not working" — the entry counts as neither work nor break.
     */
    private static final Set<String> IGNORED_TYPES = Set.of("TIME_OFF", "HOLIDAY");

    private EntryClassifier() {
    }

    public static Kind classify(TimeEntry entry, boolean fallbackDetectionEnabled) {
        String canonical = extractType(entry);
        if (canonical != null) {
            String upper = canonical.toUpperCase();
            if ("BREAK".equals(upper)) return Kind.BREAK;
            if (IGNORED_TYPES.contains(upper)) return Kind.IGNORED;
            return Kind.WORK;
        }
        if (!fallbackDetectionEnabled) {
            return Kind.WORK;
        }
        if (matchesMarker(entry.getDescription())) {
            return Kind.BREAK;
        }
        if (entry.getTags() != null) {
            for (String tag : entry.getTags()) {
                if (matchesMarker(tag)) {
                    return Kind.BREAK;
                }
            }
        }
        return Kind.WORK;
    }

    private static String extractType(TimeEntry entry) {
        Map<String, Object> raw = entry.getRaw();
        if (raw == null) {
            return null;
        }
        Object t = raw.get("type");
        if (t instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static boolean matchesMarker(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String marker : MARKERS) {
            if (containsWord(lower, marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String haystack, String needle) {
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int afterIdx = idx + needle.length();
            boolean rightOk = afterIdx == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(afterIdx));
            if (leftOk && rightOk) {
                return true;
            }
            idx = haystack.indexOf(needle, idx + 1);
        }
        return false;
    }

    /** Defensive helper if a caller passes the raw {@code List<String>} tags from JSONB. */
    public static List<String> normalizeTags(List<String> tags) {
        return tags == null ? List.of() : tags;
    }
}
