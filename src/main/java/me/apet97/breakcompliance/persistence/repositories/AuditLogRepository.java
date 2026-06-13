package me.apet97.breakcompliance.persistence.repositories;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import me.apet97.breakcompliance.persistence.entities.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    List<AuditLog> findByWorkspaceIdAndActorOrderByCreatedAtDesc(String workspaceId, String actor);

    List<AuditLog> findByWorkspaceIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String workspaceId, Instant fromInclusive, Instant toExclusive, Pageable pageable);
}
