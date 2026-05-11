package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import java.util.Optional;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookAuthTokenRepository extends JpaRepository<WebhookAuthToken, WebhookAuthToken.Pk> {

    List<WebhookAuthToken> findByWorkspaceIdAndAddonId(String workspaceId, String addonId);

    Optional<WebhookAuthToken> findByWorkspaceIdAndAddonIdAndPath(String workspaceId, String addonId, String path);
}
