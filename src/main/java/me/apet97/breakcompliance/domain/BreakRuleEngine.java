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

            if (active != null) {
                Map<String, Object> evidence = buildEvidence(segments, active.thresholdMinutes, active.requiredBreakMinutes);
                if (effectiveBreakMinutes <= 0) {
                    out.add(new FindingDraft(
                            input.workspaceId(),
                            bucket.userId(),
                            bucket.date(),
                            template.getId(),
                            Severity.VIOLATION,
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
                            Severity.VIOLATION,
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
                        Severity.VIOLATION,
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

    private static int positiveOr(Integer custom, int fallback) {
        return (custom != null && custom > 0) ? custom : fallback;
    }

    private static int nonNegativeOr(Integer custom, int fallback) {
        return (custom != null && custom >= 0) ? custom : fallback;
    }

    private static Integer positiveOrNull(Integer custom) {
        return (custom != null && custom > 0) ? custom : null;
    }

    private List<DayBucket> bucketEntries(BreakRuleEngineInput input) {
        Map<String, TreeMap<LocalDate, List<TimeEntry>>> byUser = new HashMap<>();
        for (TimeEntry entry : input.entries()) {
            if (!Objects.equals(entry.getWorkspaceId(), input.workspaceId())) {
                continue;
            }
            if (entry.getUserId() == null || entry.getUserId().isEmpty()) {
                continue;
            }
            if (entry.getEndAt() == null || entry.getStartAt() == null) {
                continue; // running or malformed
            }
            ZoneId zoneId = ZoneOffset.UTC;
            if (input.settings().getTimezoneStrategy() == TimezoneStrategy.ENTRY_TIMEZONE) {
                if (entry.getRaw() != null && entry.getRaw().get("timeInterval") instanceof Map<?, ?> ti) {
                    if (ti.get("timeZone") instanceof String tz && !tz.isBlank()) {
                        try {
                            zoneId = ZoneId.of(tz);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            LocalDate date = entry.getStartAt().atZone(zoneId).toLocalDate();
            if (date.isBefore(input.dateRangeStart()) || date.isAfter(input.dateRangeEnd())) {
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
                buckets.add(new DayBucket(userId, dayEntry.getKey(), sorted));
            }
        }
        buckets.sort(Comparator
                .comparing(DayBucket::date)
                .thenComparing(DayBucket::userId));
        return buckets;
    }

    private EvaluatedSegments evaluateSegments(DayBucket bucket, RuleTemplate template, boolean fallbackEnabled) {
        int workMinutes = 0;
        int qualifyingBreakMinutes = 0;
        int longestQualifyingBreakMinutes = 0;
        int currentRunWork = 0;
        int maxContinuousWork = 0;
        List<String> entryIds = new ArrayList<>();

        for (TimeEntry entry : bucket.entries()) {
            entryIds.add(entry.getSourceEntryId());
            int minutes = durationMinutes(entry);
            EntryClassifier.Kind kind = EntryClassifier.classify(entry, fallbackEnabled);
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
                continue;
            }
            workMinutes += minutes;
            currentRunWork += minutes;
        }
        if (currentRunWork > maxContinuousWork) {
            maxContinuousWork = currentRunWork;
        }
        return new EvaluatedSegments(
                workMinutes, qualifyingBreakMinutes, longestQualifyingBreakMinutes, maxContinuousWork, entryIds);
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
        evidence.put("maxContinuousWorkMinutes", segments.maxContinuousWorkMinutes);
        evidence.put("requiredBreakMinutes", requiredBreakMinutes);
        evidence.put("thresholdMinutes", thresholdMinutes);
        evidence.put("entryIds", segments.entryIds);
        return evidence;
    }

    private record DayBucket(String userId, LocalDate date, List<TimeEntry> entries) {
    }

    private record EvaluatedSegments(
            int workMinutes,
            int qualifyingBreakMinutes,
            int longestQualifyingBreakMinutes,
            int maxContinuousWorkMinutes,
            List<String> entryIds) {
    }

    private record ActiveRequirement(int thresholdMinutes, int requiredBreakMinutes) {
    }
}
