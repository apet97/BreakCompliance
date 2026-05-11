package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.TargetType;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.TemplateAssignmentRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AssignmentsController {

    private final TemplateAssignmentRepository assignments;
    private final RuleTemplateRepository templates;

    public AssignmentsController(TemplateAssignmentRepository assignments, RuleTemplateRepository templates) {
        this.assignments = assignments;
        this.templates = templates;
    }

    @GetMapping("/api/assignments")
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(assignments.findByWorkspaceId(claims.workspaceId()).stream()
                .map(this::toBody)
                .toList());
    }

    @PutMapping(value = "/api/assignments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> upsert(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        String targetType = stringValue(body.get("targetType"));
        String targetId = stringValue(body.get("targetId"));
        String templateId = stringValue(body.get("templateId"));
        if (targetType == null || targetId == null || templateId == null) {
            return ResponseEntity.badRequest().build();
        }
        TargetType type;
        try {
            type = TargetType.valueOf(targetType);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (templates.findById(new me.apet97.breakcompliance.persistence.entities.RuleTemplate.Pk(claims.workspaceId(), templateId)).isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        TemplateAssignment.Pk pk = new TemplateAssignment.Pk(claims.workspaceId(), type, targetId);
        TemplateAssignment a = assignments.findById(pk).orElseGet(TemplateAssignment::new);
        Instant now = Instant.now();
        if (a.getId() == null) {
            a.setId(UUID.randomUUID().toString());
            a.setCreatedAt(now);
        }
        a.setWorkspaceId(claims.workspaceId());
        a.setTargetType(type);
        a.setTargetId(targetId);
        a.setTemplateId(templateId);
        a.setUpdatedAt(now);
        assignments.save(a);
        return ResponseEntity.ok(toBody(a));
    }

    @DeleteMapping("/api/assignments")
    @Transactional
    public ResponseEntity<Void> delete(
            HttpServletRequest request,
            @RequestParam("targetType") String targetType,
            @RequestParam("targetId") String targetId) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        TargetType type;
        try {
            type = TargetType.valueOf(targetType);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        TemplateAssignment.Pk pk = new TemplateAssignment.Pk(claims.workspaceId(), type, targetId);
        if (assignments.existsById(pk)) {
            assignments.deleteById(pk);
        }
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toBody(TemplateAssignment a) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", a.getId());
        body.put("workspaceId", a.getWorkspaceId());
        body.put("targetType", a.getTargetType().name());
        body.put("targetId", a.getTargetId());
        body.put("templateId", a.getTemplateId());
        body.put("updatedAt", a.getUpdatedAt().toString());
        return body;
    }

    private static String stringValue(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
