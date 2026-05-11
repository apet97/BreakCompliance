package me.apet97.breakcompliance.domain;

import java.time.LocalDate;
import java.util.Map;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.Severity;

/**
 * Pre-storage shape produced by {@link BreakRuleEngine}. The service layer
 * stamps {@code id} and {@code createdAt} when persisting as a
 * {@link me.apet97.breakcompliance.persistence.entities.Finding} row.
 */
public record FindingDraft(
        String workspaceId,
        String userId,
        LocalDate date,
        String templateId,
        Severity severity,
        FindingCode code,
        String message,
        Map<String, Object> evidence) {
}
