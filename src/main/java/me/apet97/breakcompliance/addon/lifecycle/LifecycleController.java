package me.apet97.breakcompliance.addon.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.apet97.breakcompliance.addon.auth.ClaimsNormalizer;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lifecycle")
public class LifecycleController {

    /**
     * Top-level keys we expect in the canonical SETTINGS_UPDATED wrapper.
     * Anything outside this set emits a one-shot WARN via
     * {@link PayloadDriftLogger}; the handler still runs.
     */
    private static final Set<String> KNOWN_SETTINGS_UPDATED_KEYS =
            Set.of("settings", "workspaceId", "addonId", "asUser", "addonUserId");

    private final InstallationService installationService;
    private final ObjectMapper objectMapper;
    private final PayloadDriftLogger driftLogger;

    public LifecycleController(
            InstallationService installationService,
            ObjectMapper objectMapper,
            PayloadDriftLogger driftLogger) {
        this.installationService = installationService;
        this.objectMapper = objectMapper;
        this.driftLogger = driftLogger;
    }

    @PostMapping(value = "/installed", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> installed(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        NormalizedClaims claims = ClaimsNormalizer.enrichFromInstalledPayload(requireClaims(request), payload);
        installationService.handleInstalled(claims, payload);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/deleted")
    public ResponseEntity<Void> deleted(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        NormalizedClaims claims = requireClaims(request);
        installationService.handleDeleted(claims);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/settings-updated", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> settingsUpdated(
            HttpServletRequest request, @RequestBody(required = false) Object payload) {
        NormalizedClaims claims = requireClaims(request);
        JsonNode payloadNode = toJsonNode(payload);
        // Drift-log unknown top-level keys on the canonical wrapper shape so a
        // schema bump emits a one-shot WARN.
        if (payloadNode != null && payloadNode.isObject()) {
            driftLogger.check(
                    "lifecycle.settings-updated",
                    SettingsUpdatedPayload.asObjectMap(payloadNode, objectMapper),
                    KNOWN_SETTINGS_UPDATED_KEYS);
        }
        List<Map<String, Object>> updates = SettingsUpdatedPayload.extractUpdates(payloadNode, objectMapper);
        installationService.handleSettingsUpdated(claims, updates);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/status-changed")
    public ResponseEntity<Void> statusChanged(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        NormalizedClaims claims = requireClaims(request);
        if (payload != null) {
            Object status = payload.get("status");
            if (status instanceof String s) {
                installationService.handleStatusChanged(claims, s);
            }
        }
        return ResponseEntity.ok().build();
    }

    private static NormalizedClaims requireClaims(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null) {
            throw new IllegalStateException("normalized claims missing — lifecycle auth filter not applied");
        }
        return claims;
    }

    private JsonNode toJsonNode(Object payload) {
        if (payload == null) {
            return null;
        }
        return objectMapper.valueToTree(payload);
    }
}
