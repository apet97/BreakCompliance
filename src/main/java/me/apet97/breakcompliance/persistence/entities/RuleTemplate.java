package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Engine-irrelevant in current evaluation paths — the active engine builds a
 * transient template from {@link WorkspaceSettings} via
 * {@code BreakRuleEngine.synthesizeWorkspaceTemplate(...)}. The persisted
 * table {@code breakcompliance_rule_templates} is kept for rollback safety
 * (additive-migrations policy) and as the staging point if per-user /
 * per-group rule overrides land in the future. See CLAUDE.md "Settings
 * model" notes — don't waste time refactoring evaluation through this entity.
 */
@Entity
@Table(name = "breakcompliance_rule_templates")
@IdClass(RuleTemplate.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleTemplate {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", nullable = false)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private RuleTemplateType type;

    @Column(name = "preset_key")
    private String presetKey;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "minimum_valid_break_segment_minutes", nullable = false)
    private int minimumValidBreakSegmentMinutes;

    @Column(name = "work_threshold_minutes", nullable = false)
    private int workThresholdMinutes;

    @Column(name = "required_break_minutes", nullable = false)
    private int requiredBreakMinutes;

    @Column(name = "max_continuous_work_minutes_before_break", nullable = false)
    private int maxContinuousWorkMinutesBeforeBreak;

    @Column(name = "second_threshold_minutes")
    private Integer secondThresholdMinutes;

    @Column(name = "second_required_break_minutes")
    private Integer secondRequiredBreakMinutes;

    @Column(name = "allow_split_breaks", nullable = false)
    private boolean allowSplitBreaks = true;

    @Column(name = "grace_period_minutes", nullable = false)
    private int gracePeriodMinutes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String id;
    }
}
