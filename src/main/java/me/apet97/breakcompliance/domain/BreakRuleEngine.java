package me.apet97.breakcompliance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.GroupMembership;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.entities.TargetType;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
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
 *   <li>Resolution order: USER assignment > GROUP assignment (sorted by
 *       {@code (targetId, templateId)}; first match wins) > workspace default
 *       > {@code NO_TEMPLATE_ASSIGNED}.
 *   <li>UTC date bucketing.
 *   <li>Stable output ordering: {@code (date, userId, code)}.
 * </ul>
 */
@Component
public class BreakRuleEngine {

    public List<FindingDraft> evaluate(BreakRuleEngineInput input) {
        Context ctx = buildContext(input);
        List<DayBucket> buckets = bucketEntries(input);
        List<FindingDraft> out = new ArrayList<>();
        boolean fallbackEnabled = input.settings().isFallbackDetectionEnabled();

        for (DayBucket bucket : buckets) {
            RuleTemplate template = resolveTemplateForUser(ctx, bucket.userId());
            if (template == null) {
                out.add(new FindingDraft(
                        input.workspaceId(),
                        bucket.userId(),
                        bucket.date(),
                        "",
                        Severity.INFO,
                        FindingCode.NO_TEMPLATE_ASSIGNED,
                        "No rule template is assigned to this user and no workspace default is set.",
                        emptyEvidence(bucket)));
                continue;
            }
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

    private Context buildContext(BreakRuleEngineInput input) {
        Map<String, RuleTemplate> templatesById = new HashMap<>();
        for (RuleTemplate t : input.templates()) {
            if (Objects.equals(t.getWorkspaceId(), input.workspaceId())) {
                templatesById.put(t.getId(), t);
            }
        }

        Map<String, String> userAssignments = new HashMap<>();
        List<TemplateAssignment> groupAssignments = new ArrayList<>();
        for (TemplateAssignment a : input.assignments()) {
            if (!Objects.equals(a.getWorkspaceId(), input.workspaceId())) {
                continue;
            }
            if (a.getTargetType() == TargetType.USER) {
                userAssignments.put(a.getTargetId(), a.getTemplateId());
            } else if (a.getTargetType() == TargetType.GROUP) {
                groupAssignments.add(a);
            }
        }
        groupAssignments.sort(Comparator
                .comparing(TemplateAssignment::getTargetId)
                .thenComparing(TemplateAssignment::getTemplateId));
        // First-write-wins per targetId so iteration order is deterministic.
        Map<String, String> groupAssignmentsByGroup = new LinkedHashMap<>();
        for (TemplateAssignment a : groupAssignments) {
            groupAssignmentsByGroup.putIfAbsent(a.getTargetId(), a.getTemplateId());
        }

        Map<String, Set<String>> groupsByUser = new HashMap<>();
        for (GroupMembership m : input.groupMemberships()) {
            if (!Objects.equals(m.getWorkspaceId(), input.workspaceId())) {
                continue;
            }
            if (m.getUserId() == null || m.getUserId().isEmpty()) {
                continue;
            }
            if (m.getGroupId() == null || m.getGroupId().isEmpty()) {
                continue;
            }
            groupsByUser.computeIfAbsent(m.getUserId(), k -> new java.util.HashSet<>()).add(m.getGroupId());
        }

        String defaultTemplateId = input.settings().getDefaultTemplateId();
        return new Context(templatesById, userAssignments, groupAssignmentsByGroup, groupsByUser, defaultTemplateId);
    }

    private RuleTemplate resolveTemplateForUser(Context ctx, String userId) {
        String userTemplateId = ctx.userAssignments.get(userId);
        if (userTemplateId != null) {
            RuleTemplate t = ctx.templatesById.get(userTemplateId);
            if (t != null) {
                return t;
            }
        }
        Set<String> userGroups = ctx.groupsByUser.get(userId);
        if (userGroups != null && !userGroups.isEmpty()) {
            for (Map.Entry<String, String> e : ctx.groupAssignments.entrySet()) {
                if (userGroups.contains(e.getKey())) {
                    RuleTemplate t = ctx.templatesById.get(e.getValue());
                    if (t != null) {
                        return t;
                    }
                }
            }
        }
        if (ctx.defaultTemplateId != null) {
            return ctx.templatesById.get(ctx.defaultTemplateId);
        }
        return null;
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
            LocalDate date = entry.getStartAt().atZone(ZoneOffset.UTC).toLocalDate();
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

    private static Map<String, Object> emptyEvidence(DayBucket bucket) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("workMinutes", 0);
        evidence.put("breakMinutes", 0);
        evidence.put("maxContinuousWorkMinutes", 0);
        evidence.put("requiredBreakMinutes", 0);
        evidence.put("thresholdMinutes", 0);
        List<String> ids = new ArrayList<>();
        for (TimeEntry e : bucket.entries()) {
            ids.add(e.getSourceEntryId());
        }
        evidence.put("entryIds", ids);
        return evidence;
    }

    private record Context(
            Map<String, RuleTemplate> templatesById,
            Map<String, String> userAssignments,
            Map<String, String> groupAssignments,
            Map<String, Set<String>> groupsByUser,
            String defaultTemplateId) {
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
