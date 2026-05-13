package me.apet97.breakcompliance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic break-rule engine. Produces an advisory list of findings
 * for a (workspace, date-range) given templates, assignments, settings,
 * group memberships, and ingested time entries. Same input → same output.
 *
 * <p>Hard rules:
 *
 * <ul>
 *   <li>Detailed Report is the source of truth — never call Clockify here,
 *       never edit time entries.
 *   <li>Skip running entries (no {@code endAt}).
 *   <li>Single active template per workspace synthesised from
 *       {@link WorkspaceSettings} — no per-user template resolution.
 *   <li>Date bucketing per {@link TimezoneStrategy}: {@code ENTRY_TIMEZONE}
 *       uses {@code timeInterval.timeZone} from each entry; any other
 *       strategy falls back to UTC.
 *   <li>Stable output ordering: {@code (date, userId, code)}.
 * </ul>
 */
@Component
public class BreakRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(BreakRuleEngine.class);

    // Gap-as-break heuristic ceiling. A gap longer than this between two
    // consecutive WORK entries is treated as a new shift / long absence,
    // not a workplace break — so we don't credit it toward break minutes.
    private static final int MAX_GAP_AS_BREAK_MINUTES = 120;

    public List<FindingDraft> evaluate(BreakRuleEngineInput input) {
        List<DayBucket> buckets = bucketEntries(input);
        List<FindingDraft> out = new ArrayList<>();
        boolean fallbackEnabled = input.settings().isFallbackDetectionEnabled();

        // Single active template per workspace: build a synthetic RuleTemplate
        // from WorkspaceSettings once and evaluate every user-day bucket
        // against it. No per-user template resolution, no preset lookup —
        // the structured-settings page is the only source of thresholds.
        RuleTemplate template = synthesizeWorkspaceTemplate(input);

        for (DayBucket bucket : buckets) {
            if (!template.isEnabled()) {
                continue;
            }

            EvaluatedSegments segments = evaluateSegments(bucket, template, fallbackEnabled);
            ActiveRequirement active = pickActiveRequirement(template, segments.workMinutes);
            int effectiveBreakMinutes = pickEffectiveBreakMinutes(template, segments);

            // P2.9 — admin-configurable severity per finding code. Defaults
            // to VIOLATION (the historical engine output); blank / invalid
            // overrides fall back to VIOLATION too.
            Severity missingSev = resolveSeverity(
                    input.settings().getSeverityOverrideMissingBreak(), Severity.VIOLATION);
            Severity insufficientSev = resolveSeverity(
                    input.settings().getSeverityOverrideInsufficientBreak(), Severity.VIOLATION);
            Severity continuousSev = resolveSeverity(
                    input.settings().getSeverityOverrideMaxContinuous(), Severity.VIOLATION);

            if (active != null) {
                Map<String, Object> evidence = buildEvidence(segments, active.thresholdMinutes, active.requiredBreakMinutes);
                if (effectiveBreakMinutes <= 0) {
                    out.add(new FindingDraft(
                            input.workspaceId(),
                            bucket.userId(),
                            bucket.date(),
                            template.getId(),
                            missingSev,
                            FindingCode.MISSING_REQUIRED_BREAK,
                            "Worked " + segments.workMinutes + " minutes (threshold " + active.thresholdMinutes
                                    + ") with no qualifying break.",
                            evidence));
                } else if (effectiveBreakMinutes < active.requiredBreakMinutes) {
                    out.add(new FindingDraft(
                            input.workspaceId(),
                            bucket.userId(),
                            bucket.date(),
                            template.getId(),
                            insufficientSev,
                            FindingCode.INSUFFICIENT_BREAK_DURATION,
                            "Qualifying break minutes " + effectiveBreakMinutes + " below required "
                                    + active.requiredBreakMinutes + ".",
                            evidence));
                }
            }

            int continuousLimit = template.getMaxContinuousWorkMinutesBeforeBreak() + template.getGracePeriodMinutes();
            if (segments.maxContinuousWorkMinutes > continuousLimit) {
                int requiredForEvidence = active != null
                        ? active.requiredBreakMinutes
                        : template.getRequiredBreakMinutes();
                Map<String, Object> evidence = buildEvidence(
                        segments, template.getMaxContinuousWorkMinutesBeforeBreak(), requiredForEvidence);
                out.add(new FindingDraft(
                        input.workspaceId(),
                        bucket.userId(),
                        bucket.date(),
                        template.getId(),
                        continuousSev,
                        FindingCode.MAX_CONTINUOUS_WORK_EXCEEDED,
                        "Continuous work " + segments.maxContinuousWorkMinutes + " minutes exceeds maximum "
                                + template.getMaxContinuousWorkMinutesBeforeBreak() + ".",
                        evidence));
            }
        }

        out.sort(Comparator
                .comparing(FindingDraft::date)
                .thenComparing(FindingDraft::userId)
                .thenComparing(d -> d.code().name()));
        return out;
    }

    /**
     * Build a transient {@link RuleTemplate} from {@link WorkspaceSettings}.
     * The workspace has a single active rule template — its values live on
     * the settings row, and we synthesize a {@code RuleTemplate} so the
     * existing evaluator code paths (segment evaluation, evidence builder,
     * etc.) work without changes. Nothing here is persisted.
     *
     * <p>A column whose value is null or {@code <= 0} falls back to the
     * {@code custom-basic} preset's value so a partially-configured
     * workspace still evaluates sensibly.
     */
    private static RuleTemplate synthesizeWorkspaceTemplate(BreakRuleEngineInput input) {
        WorkspaceSettings settings = input.settings();
        RuleTemplate fallback = RuleTemplatePresets.CUSTOM_BASIC
                .toEntity(input.workspaceId(), java.time.Instant.EPOCH);

        RuleTemplate t = new RuleTemplate();
        t.setWorkspaceId(input.workspaceId());
        t.setId("workspace-active-template");
        t.setKey(settings.getAppliedPresetKey() != null
                ? settings.getAppliedPresetKey() : "custom-basic");
        t.setName("Workspace active template");
        t.setDescription("Synthetic template built from WorkspaceSettings thresholds.");
        t.setType(fallback.getType());
        t.setPresetKey(settings.getAppliedPresetKey());
        t.setVersion(1);
        t.setEnabled(true);

        t.setMinimumValidBreakSegmentMinutes(positiveOr(
                settings.getCustomMinBreakSegmentMinutes(),
                fallback.getMinimumValidBreakSegmentMinutes()));
        t.setWorkThresholdMinutes(positiveOr(
                settings.getCustomWorkThresholdMinutes(),
                fallback.getWorkThresholdMinutes()));
        t.setRequiredBreakMinutes(positiveOr(
                settings.getCustomBreakThresholdMinutes(),
                fallback.getRequiredBreakMinutes()));
        t.setMaxContinuousWorkMinutesBeforeBreak(positiveOr(
                settings.getCustomMaxContinuousWorkMinutes(),
                t.getWorkThresholdMinutes()));
        t.setSecondThresholdMinutes(positiveOrNull(settings.getCustomSecondWorkThresholdMinutes()));
        t.setSecondRequiredBreakMinutes(positiveOrNull(settings.getCustomSecondBreakThresholdMinutes()));
        t.setAllowSplitBreaks(settings.getCustomAllowSplitBreaks() != null
                ? settings.getCustomAllowSplitBreaks()
                : fallback.isAllowSplitBreaks());
        t.setGracePeriodMinutes(nonNegativeOr(
                settings.getCustomGracePeriodMinutes(),
                fallback.getGracePeriodMinutes()));

        java.time.Instant now = java.time.Instant.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    /**
     * P2.9 — parse the persisted severity override string into a
     * {@link Severity} enum value, returning {@code fallback} when the
     * stored value is null, blank, or doesn't match an enum member.
     * The lifecycle handler validates input at the boundary; this is the
     * inner safety net so an out-of-band DB edit can't crash the engine.
     */
    private static Severity resolveSeverity(String override, Severity fallback) {
        if (override == null || override.isBlank()) return fallback;
        try {
            return Severity.valueOf(override.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int positiveOr(Integer custom, int fallback) {
        return (custom != null && custom > 0) ? custom : fallback;
    }

    private static int nonNegativeOr(Integer custom, int fallback) {
        return (custom != null && custom >= 0) ? custom : fallback;
    }

    private static Integer positiveOrNull(Integer custom) {
        return (custom != null && custom > 0) ? custom : null;
    }

    /**
     * Groups entries into (user, date) buckets using the workspace's
     * {@link TimezoneStrategy}. With {@code ENTRY_TIMEZONE} the bucket date
     * is derived from {@code entry.raw.timeInterval.timeZone} (the entry's
     * own timezone string from Clockify); unparseable timezone strings fall
     * back to UTC and emit a debug log so admins notice systemic drift.
     * Cross-workspace, missing user, and running entries are silently
     * skipped — they're filtered out at the boundary, not in the engine.
     */
    private List<DayBucket> bucketEntries(BreakRuleEngineInput input) {
        Map<String, TreeMap<LocalDate, List<TimeEntry>>> byUser = new HashMap<>();
        // Count entries that have a startAt but no endAt — i.e. a still-running
        // timer that we can't evaluate yet but shouldn't pretend isn't there.
        // Surfaced via evidence so the admin sees "this user has an open
        // timer; refresh in N minutes" instead of mystery-clean days.
        Map<String, Map<LocalDate, Integer>> runningByUserDate = new HashMap<>();
        // P6.2 — workspace-scoped exemption list. Loaded once per evaluate()
        // call; the set is small (most workspaces leave it empty) and
        // membership checks are O(1).
        java.util.Set<String> exemptUserIds = input.settings().exemptUserIdSet();
        for (TimeEntry entry : input.entries()) {
            if (!Objects.equals(entry.getWorkspaceId(), input.workspaceId())) {
                continue;
            }
            if (entry.getUserId() == null || entry.getUserId().isEmpty()) {
                continue;
            }
            if (exemptUserIds.contains(entry.getUserId())) {
                continue;
            }
            if (entry.getStartAt() == null) {
                continue; // malformed
            }
            ZoneId zoneId = ZoneOffset.UTC;
            if (input.settings().getTimezoneStrategy() == TimezoneStrategy.ENTRY_TIMEZONE) {
                if (entry.getRaw() != null && entry.getRaw().get("timeInterval") instanceof Map<?, ?> ti) {
                    if (ti.get("timeZone") instanceof String tz && !tz.isBlank()) {
                        try {
                            zoneId = ZoneId.of(tz);
                        } catch (Exception e) {
                            log.debug("engine.bucket.invalid-timezone workspace={} entry={} tz={} reason={}",
                                    input.workspaceId(), entry.getSourceEntryId(), tz, e.getClass().getSimpleName());
                        }
                    }
                }
            }
            LocalDate date = entry.getStartAt().atZone(zoneId).toLocalDate();
            if (date.isBefore(input.dateRangeStart()) || date.isAfter(input.dateRangeEnd())) {
                continue;
            }
            if (entry.getEndAt() == null) {
                runningByUserDate
                        .computeIfAbsent(entry.getUserId(), k -> new HashMap<>())
                        .merge(date, 1, Integer::sum);
                continue;
            }
            byUser
                    .computeIfAbsent(entry.getUserId(), k -> new TreeMap<>())
                    .computeIfAbsent(date, k -> new ArrayList<>())
                    .add(entry);
        }

        List<DayBucket> buckets = new ArrayList<>();
        for (Map.Entry<String, TreeMap<LocalDate, List<TimeEntry>>> userEntry : byUser.entrySet()) {
            String userId = userEntry.getKey();
            for (Map.Entry<LocalDate, List<TimeEntry>> dayEntry : userEntry.getValue().entrySet()) {
                List<TimeEntry> sorted = new ArrayList<>(dayEntry.getValue());
                sorted.sort(Comparator
                        .comparing(TimeEntry::getStartAt)
                        .thenComparing(TimeEntry::getSourceEntryId));
                int running = runningByUserDate
                        .getOrDefault(userId, Map.of())
                        .getOrDefault(dayEntry.getKey(), 0);
                buckets.add(new DayBucket(userId, dayEntry.getKey(), sorted, running));
            }
        }
        // A bucket with ONLY running entries fires no rules; skip creating
        // it so we don't add evaluation cost for empty-of-finalised-entries
        // buckets. The runningEntriesSkipped count is still attached when a
        // bucket has other (finalised) entries on the same day.
        buckets.sort(Comparator
                .comparing(DayBucket::date)
                .thenComparing(DayBucket::userId));
        return buckets;
    }

    private EvaluatedSegments evaluateSegments(DayBucket bucket, RuleTemplate template, boolean fallbackEnabled) {
        int workMinutes = 0;
        int qualifyingBreakMinutes = 0;
        int syntheticBreakMinutes = 0;
        int longestQualifyingBreakMinutes = 0;
        int currentRunWork = 0;
        int maxContinuousWork = 0;
        // P1.4 — count entries whose duration straddles a calendar
        // midnight in UTC. We don't change the start-day bucketing
        // contract here, just surface the fact so admins know "this
        // looks high because a night shift rolled into the morning."
        int overnightShifts = 0;
        List<String> entryIds = new ArrayList<>();
        Instant prevWorkEndAt = null;

        for (TimeEntry entry : bucket.entries()) {
            entryIds.add(entry.getSourceEntryId());
            int minutes = durationMinutes(entry);
            if (entry.getStartAt() != null && entry.getEndAt() != null
                    && !entry.getStartAt().atZone(ZoneOffset.UTC).toLocalDate()
                            .equals(entry.getEndAt().atZone(ZoneOffset.UTC).toLocalDate())) {
                overnightShifts++;
            }
            EntryClassifier.Kind kind = EntryClassifier.classify(entry, fallbackEnabled);
            if (kind == EntryClassifier.Kind.IGNORED) {
                // TIME_OFF / HOLIDAY entries are not work and not breaks —
                // the user wasn't on the clock. Don't reset the continuous-work
                // counter either (an IGNORED block that lands mid-day shouldn't
                // accidentally satisfy a break requirement). Also drop the
                // prevWorkEnd marker so the gap-as-break heuristic does not
                // synthesise a break across PTO/holiday windows.
                prevWorkEndAt = null;
                continue;
            }
            if (kind == EntryClassifier.Kind.BREAK) {
                if (minutes >= template.getMinimumValidBreakSegmentMinutes()) {
                    qualifyingBreakMinutes += minutes;
                    if (minutes > longestQualifyingBreakMinutes) {
                        longestQualifyingBreakMinutes = minutes;
                    }
                    if (currentRunWork > maxContinuousWork) {
                        maxContinuousWork = currentRunWork;
                    }
                    currentRunWork = 0;
                }
                // An explicit break already accounts for the elapsed time —
                // don't synthesise a gap-break across it on the next WORK.
                prevWorkEndAt = null;
                continue;
            }
            // WORK entry. Before adding it, see if the gap from the previous
            // WORK entry's end to this entry's start qualifies as a
            // synthesised break (fallback heuristic).
            Instant entryStart = entry.getStartAt();
            if (fallbackEnabled && prevWorkEndAt != null && entryStart != null && !entryStart.isBefore(prevWorkEndAt)) {
                long gapSeconds = java.time.Duration.between(prevWorkEndAt, entryStart).getSeconds();
                int gapMinutes = (int) (gapSeconds / 60L);
                if (gapMinutes >= template.getMinimumValidBreakSegmentMinutes()
                        && gapMinutes <= MAX_GAP_AS_BREAK_MINUTES) {
                    qualifyingBreakMinutes += gapMinutes;
                    syntheticBreakMinutes += gapMinutes;
                    if (gapMinutes > longestQualifyingBreakMinutes) {
                        longestQualifyingBreakMinutes = gapMinutes;
                    }
                    if (currentRunWork > maxContinuousWork) {
                        maxContinuousWork = currentRunWork;
                    }
                    currentRunWork = 0;
                }
            }
            workMinutes += minutes;
            currentRunWork += minutes;
            prevWorkEndAt = entry.getEndAt();
        }
        if (currentRunWork > maxContinuousWork) {
            maxContinuousWork = currentRunWork;
        }
        return new EvaluatedSegments(
                workMinutes,
                qualifyingBreakMinutes,
                longestQualifyingBreakMinutes,
                maxContinuousWork,
                syntheticBreakMinutes,
                entryIds,
                bucket.runningEntriesSkipped(),
                overnightShifts);
    }

    private static int durationMinutes(TimeEntry entry) {
        Long durationSeconds = entry.getDurationSeconds();
        if (durationSeconds != null && durationSeconds >= 0) {
            return (int) (durationSeconds / 60L);
        }
        if (entry.getStartAt() != null && entry.getEndAt() != null && !entry.getEndAt().isBefore(entry.getStartAt())) {
            long seconds = Instant.from(entry.getStartAt()).until(entry.getEndAt(), java.time.temporal.ChronoUnit.SECONDS);
            return (int) Math.max(0, seconds / 60L);
        }
        return 0;
    }

    private ActiveRequirement pickActiveRequirement(RuleTemplate template, int workMinutes) {
        int grace = template.getGracePeriodMinutes();
        if (template.getSecondThresholdMinutes() != null
                && template.getSecondRequiredBreakMinutes() != null
                && workMinutes > template.getSecondThresholdMinutes() + grace) {
            return new ActiveRequirement(
                    template.getSecondThresholdMinutes(), template.getSecondRequiredBreakMinutes());
        }
        if (workMinutes > template.getWorkThresholdMinutes() + grace) {
            return new ActiveRequirement(template.getWorkThresholdMinutes(), template.getRequiredBreakMinutes());
        }
        return null;
    }

    private static int pickEffectiveBreakMinutes(RuleTemplate template, EvaluatedSegments segments) {
        return template.isAllowSplitBreaks() ? segments.qualifyingBreakMinutes : segments.longestQualifyingBreakMinutes;
    }

    private static Map<String, Object> buildEvidence(
            EvaluatedSegments segments, int thresholdMinutes, int requiredBreakMinutes) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("workMinutes", segments.workMinutes);
        evidence.put("breakMinutes", segments.qualifyingBreakMinutes);
        evidence.put("syntheticBreakMinutes", segments.syntheticBreakMinutes);
        evidence.put("maxContinuousWorkMinutes", segments.maxContinuousWorkMinutes);
        evidence.put("requiredBreakMinutes", requiredBreakMinutes);
        evidence.put("thresholdMinutes", thresholdMinutes);
        evidence.put("entryIds", segments.entryIds);
        // Skipped running timers — sidebar renders a footnote when > 0 so
        // admins know to refresh once the timer is stopped.
        if (segments.runningEntriesSkipped > 0) {
            evidence.put("runningEntriesSkipped", segments.runningEntriesSkipped);
        }
        // P1.4 — flag when one or more entries on this day crossed a UTC
        // midnight. The engine still buckets to the start-day; this just
        // tells the sidebar "the work-minutes here include the tail of an
        // overnight shift, double-check before acting on the finding."
        if (segments.overnightShifts > 0) {
            evidence.put("overnightShifts", segments.overnightShifts);
        }
        return evidence;
    }

    private record DayBucket(
            String userId, LocalDate date, List<TimeEntry> entries, int runningEntriesSkipped) {
    }

    private record EvaluatedSegments(
            int workMinutes,
            int qualifyingBreakMinutes,
            int longestQualifyingBreakMinutes,
            int maxContinuousWorkMinutes,
            int syntheticBreakMinutes,
            List<String> entryIds,
            int runningEntriesSkipped,
            int overnightShifts) {
    }

    private record ActiveRequirement(int thresholdMinutes, int requiredBreakMinutes) {
    }
}
