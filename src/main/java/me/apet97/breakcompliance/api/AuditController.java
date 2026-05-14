package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.AuditLog;
import me.apet97.breakcompliance.persistence.repositories.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P4.3 — Admin-only audit log viewer. Surfaces rows already written by the
 * preset-apply + finding-review code paths (CLAUDE.md notes the audit table
 * existed without a UI). Workspace-scoped + admin-gated; bounded by a date
 * range so a chatty workspace can't blow out the response.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    private final AuditLogRepository auditRepo;

    public AuditController(AuditLogRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            HttpServletRequest request,
            @RequestParam("dateRangeStart") String fromIso,
            @RequestParam("dateRangeEnd") String toIso,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.requireAdmin(claims);
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(fromIso, toIso);
        LocalDate from = range.from();
        LocalDate to = range.to();
        int cappedLimit = Math.max(1, Math.min(MAX_LIMIT, limit > 0 ? limit : DEFAULT_LIMIT));
        // toExclusive: midnight UTC of the day AFTER `to` so the full
        // selected end-day is included.
        Instant fromInclusive = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<AuditLog> rows = auditRepo.findByWorkspaceIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                claims.workspaceId(), fromInclusive, toExclusive, PageRequest.of(0, cappedLimit));
        List<Map<String, Object>> body = rows.stream()
                .map(AuditController::toJsonShape)
                .toList();
        return ResponseEntity.ok(Map.of(
                "audit", body,
                "limit", cappedLimit,
                "truncated", body.size() == cappedLimit));
    }

    private static Map<String, Object> toJsonShape(AuditLog row) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("id", row.getId().toString());
        shape.put("actor", row.getActor());
        shape.put("action", row.getAction());
        shape.put("entityType", row.getEntityType());
        shape.put("entityId", row.getEntityId());
        shape.put("details", row.getDetails() != null ? row.getDetails() : Map.of());
        shape.put("createdAt", row.getCreatedAt() != null ? row.getCreatedAt().toString() : null);
        return shape;
    }
}
