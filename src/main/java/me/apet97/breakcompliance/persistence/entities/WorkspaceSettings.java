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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
