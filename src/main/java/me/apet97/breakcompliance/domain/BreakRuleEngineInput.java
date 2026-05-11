package me.apet97.breakcompliance.domain;

import java.time.LocalDate;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.GroupMembership;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;

public record BreakRuleEngineInput(
        String workspaceId,
        WorkspaceSettings settings,
        List<RuleTemplate> templates,
        List<TemplateAssignment> assignments,
        List<TimeEntry> entries,
        List<GroupMembership> groupMemberships,
        LocalDate dateRangeStart,
        LocalDate dateRangeEnd) {

    public BreakRuleEngineInput {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId required");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings required");
        }
        templates = templates == null ? List.of() : List.copyOf(templates);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        entries = entries == null ? List.of() : List.copyOf(entries);
        groupMemberships = groupMemberships == null ? List.of() : List.copyOf(groupMemberships);
        if (dateRangeStart == null || dateRangeEnd == null) {
            throw new IllegalArgumentException("date range required");
        }
        if (dateRangeStart.isAfter(dateRangeEnd)) {
            throw new IllegalArgumentException("dateRangeStart must be <= dateRangeEnd");
        }
    }
}
