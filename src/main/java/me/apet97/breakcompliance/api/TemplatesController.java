package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.domain.RuleTemplatePresets;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.TemplateAssignmentRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TemplatesController {

    private final RuleTemplateRepository templates;
    private final TemplateAssignmentRepository assignments;
    private final WorkspaceSettingsRepository settings;

    public TemplatesController(
            RuleTemplateRepository templates,
            TemplateAssignmentRepository assignments,
            WorkspaceSettingsRepository settings) {
        this.templates = templates;
        this.assignments = assignments;
        this.settings = settings;
    }

    @GetMapping("/api/templates")
    @Transactional
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        seedBuiltInsIfMissing(claims.workspaceId());
        List<Map<String, Object>> body = templates.findByWorkspaceId(claims.workspaceId()).stream()
                .map(this::toBody)
                .toList();
        return ResponseEntity.ok(Map.of("templates", body));
    }

    @PostMapping(value = "/api/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        String name = stringValue(body.get("name"));
        if (name == null) {
            return ResponseEntity.badRequest().body(error("name required"));
        }
        RuleTemplate t = new RuleTemplate();
        t.setWorkspaceId(claims.workspaceId());
        t.setId(UUID.randomUUID().toString());
        t.setKey(slug(name) + "-" + Long.toHexString(System.currentTimeMillis()));
        t.setName(name);
        t.setDescription(stringValueOrEmpty(body.get("description")));
        t.setType(RuleTemplateType.CUSTOM);
        t.setEnabled(boolValue(body.get("enabled"), true));
        t.setAllowSplitBreaks(boolValue(body.get("allowSplitBreaks"), true));
        t.setMinimumValidBreakSegmentMinutes(intValue(body.get("minimumValidBreakSegmentMinutes"), 5));
        t.setWorkThresholdMinutes(intValue(body.get("workThresholdMinutes"), 240));
        t.setRequiredBreakMinutes(intValue(body.get("requiredBreakMinutes"), 15));
        t.setMaxContinuousWorkMinutesBeforeBreak(intValue(body.get("maxContinuousWorkMinutesBeforeBreak"), 240));
        t.setSecondThresholdMinutes(nullableInt(body.get("secondThresholdMinutes")));
        t.setSecondRequiredBreakMinutes(nullableInt(body.get("secondRequiredBreakMinutes")));
        t.setGracePeriodMinutes(intValue(body.get("gracePeriodMinutes"), 5));
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        templates.save(t);
        return ResponseEntity.ok(toBody(t));
    }

    @PatchMapping(value = "/api/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> patch(
            HttpServletRequest request, @RequestParam("id") String id, @RequestBody Map<String, Object> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RuleTemplate t = templates.findById(new RuleTemplate.Pk(claims.workspaceId(), id)).orElse(null);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }
        if (t.getType() == RuleTemplateType.BUILT_IN) {
            return ResponseEntity.badRequest().body(error("built-in templates are read-only"));
        }

        if (body.containsKey("enabled")) {
            t.setEnabled(boolValue(body.get("enabled"), t.isEnabled()));
        }
        if (body.containsKey("allowSplitBreaks")) {
            t.setAllowSplitBreaks(boolValue(body.get("allowSplitBreaks"), t.isAllowSplitBreaks()));
        }
        if (body.containsKey("minimumValidBreakSegmentMinutes")) {
            t.setMinimumValidBreakSegmentMinutes(intValue(body.get("minimumValidBreakSegmentMinutes"), t.getMinimumValidBreakSegmentMinutes()));
        }
        if (body.containsKey("workThresholdMinutes")) {
            t.setWorkThresholdMinutes(intValue(body.get("workThresholdMinutes"), t.getWorkThresholdMinutes()));
        }
        if (body.containsKey("requiredBreakMinutes")) {
            t.setRequiredBreakMinutes(intValue(body.get("requiredBreakMinutes"), t.getRequiredBreakMinutes()));
        }
        if (body.containsKey("maxContinuousWorkMinutesBeforeBreak")) {
            t.setMaxContinuousWorkMinutesBeforeBreak(intValue(body.get("maxContinuousWorkMinutesBeforeBreak"), t.getMaxContinuousWorkMinutesBeforeBreak()));
        }
        if (body.containsKey("secondThresholdMinutes")) {
            t.setSecondThresholdMinutes(nullableInt(body.get("secondThresholdMinutes")));
        }
        if (body.containsKey("secondRequiredBreakMinutes")) {
            t.setSecondRequiredBreakMinutes(nullableInt(body.get("secondRequiredBreakMinutes")));
        }
        if (body.containsKey("gracePeriodMinutes")) {
            t.setGracePeriodMinutes(intValue(body.get("gracePeriodMinutes"), t.getGracePeriodMinutes()));
        }
        t.setVersion(t.getVersion() + 1);
        t.setUpdatedAt(Instant.now());
        templates.save(t);
        return ResponseEntity.ok(toBody(t));
    }

    @DeleteMapping("/api/templates")
    @Transactional
    public ResponseEntity<Void> delete(HttpServletRequest request, @RequestParam("id") String id) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RuleTemplate t = templates.findById(new RuleTemplate.Pk(claims.workspaceId(), id)).orElse(null);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }
        if (t.getType() == RuleTemplateType.BUILT_IN) {
            return ResponseEntity.badRequest().build();
        }
        // Cascade: clear assignments referencing this template and null workspace default.
        for (var assign : assignments.findByWorkspaceIdAndTemplateId(claims.workspaceId(), id)) {
            assignments.delete(assign);
        }
        settings.findById(claims.workspaceId()).ifPresent(s -> {
            if (id.equals(s.getDefaultTemplateId())) {
                s.setDefaultTemplateId(null);
                s.setUpdatedAt(Instant.now());
                settings.save(s);
            }
        });
        templates.delete(t);
        return ResponseEntity.noContent().build();
    }

    private void seedBuiltInsIfMissing(String workspaceId) {
        if (!templates.findByWorkspaceId(workspaceId).isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (var preset : RuleTemplatePresets.ALL) {
            templates.save(preset.toEntity(workspaceId, now));
        }
    }

    private Map<String, Object> toBody(RuleTemplate t) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", t.getId());
        body.put("workspaceId", t.getWorkspaceId());
        body.put("key", t.getKey());
        body.put("name", t.getName());
        body.put("description", t.getDescription());
        body.put("type", t.getType().name());
        body.put("presetKey", t.getPresetKey());
        body.put("version", t.getVersion());
        body.put("enabled", t.isEnabled());
        body.put("minimumValidBreakSegmentMinutes", t.getMinimumValidBreakSegmentMinutes());
        body.put("workThresholdMinutes", t.getWorkThresholdMinutes());
        body.put("requiredBreakMinutes", t.getRequiredBreakMinutes());
        body.put("maxContinuousWorkMinutesBeforeBreak", t.getMaxContinuousWorkMinutesBeforeBreak());
        body.put("secondThresholdMinutes", t.getSecondThresholdMinutes());
        body.put("secondRequiredBreakMinutes", t.getSecondRequiredBreakMinutes());
        body.put("allowSplitBreaks", t.isAllowSplitBreaks());
        body.put("gracePeriodMinutes", t.getGracePeriodMinutes());
        body.put("createdAt", t.getCreatedAt().toString());
        body.put("updatedAt", t.getUpdatedAt().toString());
        return body;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    private static String stringValue(Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String stringValueOrEmpty(Object v) {
        return v instanceof String s ? s : "";
    }

    private static boolean boolValue(Object v, boolean fallback) {
        return v instanceof Boolean b ? b : fallback;
    }

    private static int intValue(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        return fallback;
    }

    private static Integer nullableInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
