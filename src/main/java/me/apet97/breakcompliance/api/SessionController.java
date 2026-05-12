package me.apet97.breakcompliance.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.config.BreakComplianceManifestProperties;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final WorkspaceSettingsRepository settingsRepo;
    private final ObjectMapper objectMapper;
    private final BreakComplianceManifestProperties manifestProps;

    public SessionController(
            WorkspaceSettingsRepository settingsRepo,
            ObjectMapper objectMapper,
            BreakComplianceManifestProperties manifestProps) {
        this.settingsRepo = settingsRepo;
        this.objectMapper = objectMapper;
        this.manifestProps = manifestProps;
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
        body.put("settingsUrl", buildSettingsUrl(claims));

        // Best-effort: include the workspace's active preset so the sidebar
        // can render "Active template: <name>" without a second round-trip.
        // Missing settings row → null; the sidebar handles that gracefully.
        if (claims.workspaceId() != null) {
            settingsRepo.findById(claims.workspaceId()).ifPresentOrElse(
                    settings -> {
                        body.put("appliedPresetKey", settings.getAppliedPresetKey());
                        body.put("activeTemplate", buildActiveTemplate(settings));
                        body.put("validationWarnings", parseValidationWarnings(settings));
                    },
                    () -> {
                        body.put("appliedPresetKey", null);
                        body.put("activeTemplate", null);
                        body.put("validationWarnings", java.util.List.of());
                    });
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Build the URL the sidebar's Settings button should open. Dev-portal
     * installs land on the per-installation page
     * ({@code developer.clockify.me/addon/<installId>/settings}); production
     * installs land on the per-workspace page
     * ({@code app.clockify.me/workspaces/<wsId>/settings/addons/<manifestKey>}).
     * The env is detected from the JWT's {@code backendUrl} claim — Clockify
     * sets it to {@code developer.clockify.me/api} in the dev portal and to
     * {@code api.clockify.me/...} in production. Returns null when any
     * required claim is missing so the sidebar can fall back to the
     * collapsible breadcrumb hint instead of opening a broken URL.
     */
    private String buildSettingsUrl(NormalizedClaims claims) {
        String backendUrl = claims.backendUrl();
        if (backendUrl == null || backendUrl.isBlank()) return null;
        boolean isDevPortal = backendUrl.contains("developer.clockify.me");
        if (isDevPortal) {
            String addonId = claims.addonId();
            if (addonId == null || addonId.isBlank()) return null;
            return "https://developer.clockify.me/addon/" + addonId + "/settings";
        }
        String workspaceId = claims.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) return null;
        return "https://app.clockify.me/workspaces/" + workspaceId
                + "/settings/addons/" + manifestProps.key();
    }

    /**
     * Decode the JSON-encoded warnings list stored on {@code WorkspaceSettings}
     * so the response carries a real nested array, not a double-encoded
     * string. Empty list when no warnings exist or the stored JSON is
     * malformed (defensive — we log and degrade silently rather than fail
     * the whole session call).
     */
    private Object parseValidationWarnings(WorkspaceSettings settings) {
        String raw = settings.getValidationWarnings();
        if (raw == null || raw.isBlank()) return java.util.List.of();
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.isArray() ? node : java.util.List.of();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("session.validation-warnings.parse-failed workspace={}",
                    settings.getWorkspaceId(), e);
            return java.util.List.of();
        }
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
