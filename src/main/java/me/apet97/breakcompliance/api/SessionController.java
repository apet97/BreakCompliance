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
 *
 * <p>Returns the inline thresholds so the sidebar can render an "Active
 * template" tooltip without a second round-trip — see the
 * {@code activeTemplate} block.
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
            settingsRepo.findById(claims.workspaceId()).ifPresentOrElse(
                    settings -> {
                        body.put("appliedPresetKey", settings.getAppliedPresetKey());
                        body.put("activeTemplate", buildActiveTemplate(settings));
                    },
                    () -> {
                        body.put("appliedPresetKey", null);
                        body.put("activeTemplate", null);
                    });
        }
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> buildActiveTemplate(WorkspaceSettings s) {
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("workThresholdMinutes", s.getCustomWorkThresholdMinutes());
        at.put("breakThresholdMinutes", s.getCustomBreakThresholdMinutes());
        at.put("minBreakSegmentMinutes", s.getCustomMinBreakSegmentMinutes());
        at.put("maxContinuousWorkMinutes", s.getCustomMaxContinuousWorkMinutes());
        at.put("gracePeriodMinutes", s.getCustomGracePeriodMinutes());
        at.put("allowSplitBreaks", s.getCustomAllowSplitBreaks());
        at.put("secondWorkThresholdMinutes", s.getCustomSecondWorkThresholdMinutes());
        at.put("secondBreakThresholdMinutes", s.getCustomSecondBreakThresholdMinutes());
        at.put("timezoneStrategy",
                s.getTimezoneStrategy() == null ? null : s.getTimezoneStrategy().name());
        at.put("fallbackDetectionEnabled", s.isFallbackDetectionEnabled());
        return at;
    }
}
