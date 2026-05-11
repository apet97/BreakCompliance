package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {

    private final WorkspaceSettingsRepository repo;

    public SettingsController(WorkspaceSettingsRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/settings")
    @Transactional
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        WorkspaceSettings settings = repo.findById(claims.workspaceId()).orElseGet(() -> createDefaults(claims.workspaceId()));
        return ResponseEntity.ok(toBody(settings));
    }

    @PatchMapping(value = "/api/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> patch(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        WorkspaceSettings settings = repo.findById(claims.workspaceId()).orElseGet(() -> createDefaults(claims.workspaceId()));

        if (body.containsKey("defaultTemplateId")) {
            Object v = body.get("defaultTemplateId");
            settings.setDefaultTemplateId(v == null ? null : v.toString());
        }
        if (body.containsKey("fallbackDetectionEnabled")) {
            Object v = body.get("fallbackDetectionEnabled");
            if (!(v instanceof Boolean b)) {
                return ResponseEntity.badRequest().build();
            }
            settings.setFallbackDetectionEnabled(b);
        }
        for (String key : body.keySet()) {
            if (!key.equals("defaultTemplateId") && !key.equals("fallbackDetectionEnabled")) {
                return ResponseEntity.badRequest().build();
            }
        }
        settings.setUpdatedAt(Instant.now());
        repo.save(settings);
        return ResponseEntity.ok(toBody(settings));
    }

    private WorkspaceSettings createDefaults(String workspaceId) {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(workspaceId);
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(false);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return repo.save(s);
    }

    private Map<String, Object> toBody(WorkspaceSettings s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", s.getWorkspaceId());
        body.put("defaultTemplateId", s.getDefaultTemplateId());
        body.put("timezoneStrategy", s.getTimezoneStrategy().name());
        body.put("fallbackDetectionEnabled", s.isFallbackDetectionEnabled());
        body.put("updatedAt", s.getUpdatedAt().toString());
        return body;
    }
}
