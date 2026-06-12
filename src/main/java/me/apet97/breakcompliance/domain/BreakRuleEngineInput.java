package me.apet97.breakcompliance.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;

/**
 * P1.1 / P1.2 — suppression sets are passed through the input so the
 * engine itself stays pure. {@code workspaceWideHolidayDates} short-circuits
 * every (userId, date) bucket whose date is in the set;
 * {@code userSpecificSuppressedDates} suppresses only the matching user's
 * day (used for per-user holiday assignments AND approved time-off
 * windows). Both default to empty for callers that don't care.
 */
public record BreakRuleEngineInput(
        String workspaceId,
        WorkspaceSettings settings,
        List<TimeEntry> entries,
        LocalDate dateRangeStart,
        LocalDate dateRangeEnd,
        Set<LocalDate> workspaceWideHolidayDates,
        Map<String, Set<LocalDate>> userSpecificSuppressedDates) {

    public BreakRuleEngineInput {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId required");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings required");
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (dateRangeStart == null || dateRangeEnd == null) {
            throw new IllegalArgumentException("date range required");
        }
        if (dateRangeStart.isAfter(dateRangeEnd)) {
            throw new IllegalArgumentException("dateRangeStart must be <= dateRangeEnd");
        }
        workspaceWideHolidayDates = workspaceWideHolidayDates == null
                ? Set.of() : Set.copyOf(workspaceWideHolidayDates);
        userSpecificSuppressedDates = userSpecificSuppressedDates == null
                ? Map.of() : Map.copyOf(userSpecificSuppressedDates);
    }

    /** Convenience constructor for callers that do not need suppression. */
    public BreakRuleEngineInput(
            String workspaceId,
            WorkspaceSettings settings,
            List<TimeEntry> entries,
            LocalDate dateRangeStart,
            LocalDate dateRangeEnd) {
        this(workspaceId, settings, entries, dateRangeStart, dateRangeEnd, Set.of(), Map.of());
    }
}
