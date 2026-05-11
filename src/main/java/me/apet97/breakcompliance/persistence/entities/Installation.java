package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;

@Entity
@Table(name = "breakcompliance_installations")
@IdClass(Installation.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Installation {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "addon_id", nullable = false)
    private String addonId;

    @Embedded
    private EncryptedToken authToken;

    @Column(name = "backend_url", nullable = false)
    private String backendUrl;

    @Column(name = "reports_url")
    private String reportsUrl;

    @Column(name = "installer_user_id")
    private String installerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InstallationStatus status = InstallationStatus.ACTIVE;

    @Column(name = "installed_at", nullable = false)
    private Instant installedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String addonId;
    }
}
