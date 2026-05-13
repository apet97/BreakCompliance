package me.apet97.breakcompliance.persistence.repositories;

import java.time.LocalDate;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface FindingRepository extends JpaRepository<Finding, Finding.Pk> {

    List<Finding> findByWorkspaceIdAndDateBetween(String workspaceId, LocalDate from, LocalDate to);

    // P6.1 — DSAR aggregation: every finding ever produced for a user.
    List<Finding> findByWorkspaceIdAndUserId(String workspaceId, String userId);

    @Modifying
    @Transactional
    long deleteByWorkspaceIdAndDateBetween(String workspaceId, LocalDate from, LocalDate to);
}
