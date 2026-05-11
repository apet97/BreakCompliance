package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
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
@Table(name = "breakcompliance_webhook_auth_tokens")
@IdClass(WebhookAuthToken.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WebhookAuthToken {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "addon_id", nullable = false)
    private String addonId;

    @Id
    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Embedded
    private EncryptedToken authToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String addonId;
        private String path;
    }
}
