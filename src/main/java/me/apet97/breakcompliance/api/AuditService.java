package me.apet97.breakcompliance.api;

import java.util.Map;
import me.apet97.breakcompliance.persistence.entities.AuditLog;
import me.apet97.breakcompliance.persistence.repositories.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes audit rows for admin-state-changing API calls so the new
 * {@link AuditController} (P4.3) has something to render. Best-effort —
 * never throws; a failed audit-write logs at WARN but doesn't break the
 * user-facing call that triggered it. Reasoning: audit visibility is
 * valuable but losing one row is preferable to refusing a preset-apply
 * because the audit insert failed.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void record(
            String workspaceId,
            String actor,
            String action,
            String entityType,
            String entityId,
            Map<String, Object> details) {
        try {
            AuditLog row = new AuditLog();
            row.setWorkspaceId(workspaceId);
            row.setActor(actor);
            row.setAction(action);
            row.setEntityType(entityType);
            row.setEntityId(entityId);
            row.setDetails(details != null ? details : Map.of());
            repo.saveAndFlush(row);
        } catch (RuntimeException e) {
            log.warn(
                    "audit.write-failed workspace={} action={} entity={}:{} reason={}",
                    workspaceId,
                    action,
                    entityType,
                    entityId,
                    e.getClass().getSimpleName(),
                    e);
        }
    }
}
