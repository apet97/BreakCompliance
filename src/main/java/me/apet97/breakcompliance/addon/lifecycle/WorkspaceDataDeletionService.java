package me.apet97.breakcompliance.addon.lifecycle;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceDataDeletionService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDataDeletionService.class);
    private final EntityManager em;

    public WorkspaceDataDeletionService(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public void deleteWorkspaceData(String workspaceId) {
        log.info("Deleting all app data for workspace={}", workspaceId);

        // Delete from all workspace-bound entities
        em.createQuery("DELETE FROM AuditLog a WHERE a.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM FindingReview f WHERE f.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM Finding f WHERE f.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM TimeEntry t WHERE t.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM IngestionRun i WHERE i.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM RefreshSignal r WHERE r.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM GroupMembership g WHERE g.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM TemplateAssignment t WHERE t.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM RuleTemplate r WHERE r.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        em.createQuery("DELETE FROM WorkspaceSettings w WHERE w.workspaceId = :wId").setParameter("wId", workspaceId).executeUpdate();
        // Webhook tokens might be cascaded but deleting them explicitly is fine.
        // The FK cascade is from Installation.
    }
}
