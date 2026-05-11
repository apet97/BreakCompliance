package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the verified user-iframe claims to the sidebar JS so admins can
 * be gated client-side (defense-in-depth — the server gates separately on
 * workspaceRole). Returning the raw token is deliberately avoided.
 *
 * <p>Also surfaces {@code appliedPresetKey} (the workspace's currently
 * active rule template preset) and {@code addonId} (so the sidebar's
 * "Settings" button can build the env-correct settings-page URL — different
 * shape on production vs. the developer portal).
 */
@RestController
public class SessionController {

    private final WorkspaceSettingsRepository settingsRepo;

    public SessionController(WorkspaceSettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    @GetMapping("/api/session")
    public ResponseEntity<Map<String, Object>> session(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", claims.workspaceId());
        body.put("userId", claims.userId());
        body.put("workspaceRole", claims.workspaceRole());
        body.put("addonId", claims.addonId());

        // Best-effort: include the workspace's active preset so the sidebar
        // can render "Active template: <name>" without a second round-trip.
        // Missing settings row → null; the sidebar handles that gracefully.
        if (claims.workspaceId() != null) {
            String appliedPresetKey = settingsRepo.findById(claims.workspaceId())
                    .map(WorkspaceSettings::getAppliedPresetKey)
                    .orElse(null);
            body.put("appliedPresetKey", appliedPresetKey);
        }
        return ResponseEntity.ok(body);
    }
}
