package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "breakcompliance_workspace_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceSettings {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "default_template_id")
    private String defaultTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "timezone_strategy", nullable = false)
    private TimezoneStrategy timezoneStrategy = TimezoneStrategy.ENTRY_TIMEZONE;

    @Column(name = "fallback_detection_enabled", nullable = false)
    private boolean fallbackDetectionEnabled = false;

    @Column(name = "custom_policy_enabled", nullable = false)
    private boolean customPolicyEnabled = false;

    @Column(name = "custom_work_threshold_minutes")
    private Integer customWorkThresholdMinutes;

    @Column(name = "custom_break_threshold_minutes")
    private Integer customBreakThresholdMinutes;

    @Column(name = "custom_min_break_segment_minutes")
    private Integer customMinBreakSegmentMinutes;

    @Column(name = "custom_max_continuous_work_minutes")
    private Integer customMaxContinuousWorkMinutes;

    @Column(name = "custom_grace_period_minutes")
    private Integer customGracePeriodMinutes;

    @Column(name = "custom_allow_split_breaks")
    private Boolean customAllowSplitBreaks;

    @Column(name = "custom_second_work_threshold_minutes")
    private Integer customSecondWorkThresholdMinutes;

    @Column(name = "custom_second_break_threshold_minutes")
    private Integer customSecondBreakThresholdMinutes;

    /**
     * Last preset the admin selected from the structured-settings preset
     * dropdown. Used by {@code InstallationService.handleSettingsUpdated}
     * to detect "preset changed" vs. "admin edited fields" — only the
     * former triggers an overwrite of the threshold columns with the
     * preset's values.
     */
    @Column(name = "applied_preset_key", nullable = false)
    private String appliedPresetKey = "custom-basic";

    /**
     * JSON-encoded list of {@code SettingsWarning} produced by the most
     * recent {@code SETTINGS_UPDATED} delivery. Populated by
     * {@code InstallationService.handleSettingsUpdated} and surfaced via
     * {@code SessionController} so the sidebar renders a warning banner
     * when admin-saved thresholds are internally inconsistent. Null when
     * no warnings have been produced (fresh workspaces or fully-consistent
     * saves).
     */
    @Column(name = "validation_warnings", columnDefinition = "TEXT")
    private String validationWarnings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
