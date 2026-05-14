package me.apet97.breakcompliance.persistence.repositories;

import java.time.Instant;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.WorkspaceTimeOff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceTimeOffRepository extends JpaRepository<WorkspaceTimeOff, WorkspaceTimeOff.Pk> {

    /**
     * Approved time-off rows whose start/end overlaps the given range,
     * scoped to a single workspace.
     */
    List<WorkspaceTimeOff> findByWorkspaceIdAndStartAtLessThanAndEndAtGreaterThanEqual(
            String workspaceId, Instant rangeEnd, Instant rangeStart);

    // P6.1 — user-scoped time-off rows for DSAR export.
    List<WorkspaceTimeOff> findByWorkspaceIdAndUserId(String workspaceId, String userId);

    // Replace-on-refetch: drop rows that overlap the refreshed window so
    // a withdrawn or rejected request stops suppressing its dates on the
    // next ingest. The repeated upsert by PK then re-populates whichever
    // requests are still approved.
    @Modifying
    @Transactional
    long deleteByWorkspaceIdAndStartAtLessThanAndEndAtGreaterThanEqual(
            String workspaceId, Instant rangeEnd, Instant rangeStart);
}
