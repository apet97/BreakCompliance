package me.apet97.breakcompliance.persistence.repositories;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSignalRepository extends JpaRepository<RefreshSignal, RefreshSignal.Pk> {

    List<RefreshSignal> findByWorkspaceIdOrderByReceivedAtDesc(String workspaceId);

    /**
     * Drain query for the refresh-signal consumer: returns all signals in
     * the given status whose {@code receivedAt} is older than the
     * supplied cutoff. The {@code WHERE status = 'PENDING'} partial index
     * from V10 backs the typical {@code (PENDING, now-debounce)} call.
     */
    List<RefreshSignal> findByStatusAndReceivedAtBeforeOrderByWorkspaceIdAscReceivedAtAsc(
            RefreshSignalStatus status, Instant cutoff);

    /**
     * Bulk-transition a set of signals to a target status. Optional
     * {@code runId} populates the back-pointer so the consumer can later
     * trace which run owned the signal. Returns the row count for caller
     * sanity-checks (the {@code @Transactional} boundary lives on the
     * caller; this query commits as part of that scope).
     */
    @Modifying
    @Query("update RefreshSignal s set s.status = :status, s.ingestionRunId = :runId "
            + "where s.workspaceId = :workspaceId and s.id in :ids")
    int updateStatusAndRunId(
            @Param("workspaceId") String workspaceId,
            @Param("ids") Collection<String> ids,
            @Param("status") RefreshSignalStatus status,
            @Param("runId") String runId);

    /**
     * Transition a set of signals to a target status without touching the
     * existing {@code ingestionRunId}. Used by the post-finalize callback
     * to flip CLAIMED → CONSUMED while preserving the run-id back-pointer.
     */
    @Modifying
    @Query("update RefreshSignal s set s.status = :status "
            + "where s.workspaceId = :workspaceId and s.id in :ids")
    int updateStatus(
            @Param("workspaceId") String workspaceId,
            @Param("ids") Collection<String> ids,
            @Param("status") RefreshSignalStatus status);

    /**
     * Conditional transition: flip only rows currently in
     * {@code fromStatus}. The consumer uses this to safely pre-CLAIM
     * PENDING rows around the async dispatch — in production the rows
     * are still PENDING so the CLAIM lands, but a synchronous test
     * executor may have already flipped them to CONSUMED inside the
     * callback, in which case the CLAIM is correctly a no-op.
     */
    @Modifying
    @Query("update RefreshSignal s set s.status = :toStatus, s.ingestionRunId = :runId "
            + "where s.workspaceId = :workspaceId and s.id in :ids and s.status = :fromStatus")
    int transitionStatusIfCurrentlyIn(
            @Param("workspaceId") String workspaceId,
            @Param("ids") Collection<String> ids,
            @Param("fromStatus") RefreshSignalStatus fromStatus,
            @Param("toStatus") RefreshSignalStatus toStatus,
            @Param("runId") String runId);

    /**
     * Release all CLAIMED signals pointing at a stuck run back to
     * PENDING (clearing the run-id back-pointer) so the consumer's
     * next poll can re-dispatch them onto a fresh run. Called by the
     * stuck-run reaper as part of recovering from a JVM/executor
     * crash mid-ingest.
     */
    @Modifying
    @Query("update RefreshSignal s set s.status = me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus.PENDING, "
            + "s.ingestionRunId = null "
            + "where s.workspaceId = :workspaceId and s.ingestionRunId = :runId "
            + "and s.status = me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus.CLAIMED")
    int releaseClaimedSignalsForRun(
            @Param("workspaceId") String workspaceId, @Param("runId") String runId);
}
