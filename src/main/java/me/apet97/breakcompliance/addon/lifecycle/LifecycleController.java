package me.apet97.breakcompliance.addon.lifecycle;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
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

    private final InstallationService installationService;

    public LifecycleController(InstallationService installationService) {
        this.installationService = installationService;
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
            HttpServletRequest request, @RequestBody(required = false) List<Map<String, Object>> payload) {
        NormalizedClaims claims = requireClaims(request);
        installationService.handleSettingsUpdated(claims, payload == null ? List.of() : payload);
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
}
