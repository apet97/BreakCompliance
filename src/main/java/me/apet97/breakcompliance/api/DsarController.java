package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.AuditLog;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import me.apet97.breakcompliance.persistence.entities.WorkspaceTimeOff;
import me.apet97.breakcompliance.persistence.repositories.AuditLogRepository;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceTimeOffRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P6.1 — Data Subject Access Request export. Returns workspace-scoped rows
 * that reference the requested {@code userId}, including audit logs where
 * that user is the actor. Workspace boundary is the JWT's workspaceId;
 * admin-only.
 *
 * <p>Pairs with the workspace-wide wipe documented in
 * {@code docs/DATA_RETENTION.md}. Operators can hand a user the JSON file
 * this returns to satisfy GDPR Art. 15 (right of access) and Art. 20
 * (data portability) without writing a custom query.
 */
@RestController
@RequestMapping("/api/dsar")
public class DsarController {

    private final TimeEntryRepository entriesRepo;
    private final FindingRepository findingsRepo;
    private final WorkspaceHolidayRepository holidayRepo;
    private final WorkspaceTimeOffRepository timeOffRepo;
    private final AuditLogRepository auditRepo;

    public DsarController(
            TimeEntryRepository entriesRepo,
            FindingRepository findingsRepo,
            WorkspaceHolidayRepository holidayRepo,
            WorkspaceTimeOffRepository timeOffRepo,
            AuditLogRepository auditRepo) {
        this.entriesRepo = entriesRepo;
        this.findingsRepo = findingsRepo;
        this.holidayRepo = holidayRepo;
        this.timeOffRepo = timeOffRepo;
        this.auditRepo = auditRepo;
    }

    @GetMapping(value = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> export(
            HttpServletRequest request, @PathVariable("userId") String userId) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.requireAdmin(claims);
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_user_id"));
        }
        String workspaceId = claims.workspaceId();

        List<TimeEntry> entries = entriesRepo.findByWorkspaceIdAndUserId(workspaceId, userId);
        List<Finding> findings = findingsRepo.findByWorkspaceIdAndUserId(workspaceId, userId);
        List<WorkspaceHoliday> holidays =
                holidayRepo.findByWorkspaceIdAndAppliesToUserId(workspaceId, userId);
        List<WorkspaceTimeOff> timeOff = timeOffRepo.findByWorkspaceIdAndUserId(workspaceId, userId);
        List<AuditLog> auditLogs =
                auditRepo.findByWorkspaceIdAndActorOrderByCreatedAtDesc(workspaceId, userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "break-compliance/dsar/v1");
        body.put("generatedAt", Instant.now().toString());
        body.put("workspaceId", workspaceId);
        body.put("userId", userId);
        body.put("counts", Map.of(
                "timeEntries", entries.size(),
                "findings", findings.size(),
                "holidayAssignments", holidays.size(),
                "timeOffRequests", timeOff.size(),
                "auditLogs", auditLogs.size()));
        body.put("timeEntries", entries.stream().map(DsarController::toTimeEntryShape).toList());
        body.put("findings", findings.stream().map(DsarController::toFindingShape).toList());
        body.put("holidayAssignments", holidays.stream().map(DsarController::toHolidayShape).toList());
        body.put("timeOffRequests", timeOff.stream().map(DsarController::toTimeOffShape).toList());
        body.put("auditLogs", auditLogs.stream().map(DsarController::toAuditLogShape).toList());

        // Suggest a filename so operators can hand the user a self-
        // describing artefact instead of "response.json".
        String filename = "dsar-" + workspaceId + "-" + userId + "-"
                + Instant.now().toString().replace(':', '-') + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    private static Map<String, Object> toTimeEntryShape(TimeEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceEntryId", e.getSourceEntryId());
        m.put("userId", e.getUserId());
        m.put("userName", e.getUserName());
        m.put("projectId", e.getProjectId());
        m.put("taskId", e.getTaskId());
        m.put("description", e.getDescription());
        m.put("startAt", e.getStartAt() != null ? e.getStartAt().toString() : null);
        m.put("endAt", e.getEndAt() != null ? e.getEndAt().toString() : null);
        m.put("durationSeconds", e.getDurationSeconds());
        m.put("billable", e.getBillable());
        m.put("tags", e.getTags());
        m.put("ingestedAt", e.getIngestedAt() != null ? e.getIngestedAt().toString() : null);
        return m;
    }

    private static Map<String, Object> toFindingShape(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("date", f.getDate() != null ? f.getDate().toString() : null);
        m.put("severity", f.getSeverity() != null ? f.getSeverity().name() : null);
        m.put("code", f.getCode() != null ? f.getCode().name() : null);
        m.put("message", f.getMessage());
        m.put("templateId", f.getTemplateId());
        m.put("evidence", f.getEvidence() != null ? f.getEvidence() : Map.of());
        m.put("createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : null);
        return m;
    }

    private static Map<String, Object> toHolidayShape(WorkspaceHoliday h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceId", h.getSourceId());
        m.put("date", h.getDate() != null ? h.getDate().toString() : null);
        m.put("name", h.getName());
        return m;
    }

    private static Map<String, Object> toTimeOffShape(WorkspaceTimeOff t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceId", t.getSourceId());
        m.put("startAt", t.getStartAt() != null ? t.getStartAt().toString() : null);
        m.put("endAt", t.getEndAt() != null ? t.getEndAt().toString() : null);
        m.put("status", t.getStatus());
        return m;
    }

    private static Map<String, Object> toAuditLogShape(AuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId() != null ? a.getId().toString() : null);
        m.put("actor", a.getActor());
        m.put("action", a.getAction());
        m.put("entityType", a.getEntityType());
        m.put("entityId", a.getEntityId());
        m.put("details", a.getDetails() != null ? a.getDetails() : Map.of());
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        return m;
    }
}
