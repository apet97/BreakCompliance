package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import java.util.Optional;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, IngestionRun.Pk> {

    List<IngestionRun> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    /**
     * Most recent run for the workspace in a given status, ordered by
     * {@code completedAt} desc (the moment the ingest finished, not when
     * it was queued). Used by the sidebar staleness indicator to ask "when
     * did fresh data last land?" without paging through the full history.
     * Returns empty when the workspace has never had a run reach the
     * status — e.g. fresh install before any ingest has completed.
     */
    Optional<IngestionRun> findFirstByWorkspaceIdAndStatusOrderByCompletedAtDesc(
            String workspaceId, IngestionStatus status);

    /**
     * Look up an in-flight ingest covering the given workspace + date
     * range. The consumer uses this to coalesce a fresh batch of
     * refresh signals onto an already-running ingest instead of spawning
     * a duplicate. Date columns are stored as {@code yyyy-MM-dd} strings
     * to match {@code IngestionRun} schema; callers must format the same
     * way before calling.
     */
    Optional<IngestionRun> findFirstByWorkspaceIdAndStatusAndDateRangeStartAndDateRangeEnd(
            String workspaceId,
            IngestionStatus status,
            String dateRangeStart,
            String dateRangeEnd);

    /**
     * Find runs stuck in the given status with {@code createdAt} older
     * than the cutoff. Used by the reaper to recover from JVM restarts
     * or executor crashes mid-ingest that leave orphan {@code RUNNING}
     * rows; the consumer's dedupe check would otherwise refuse all
     * subsequent dispatches for the same date range forever.
     */
    List<IngestionRun> findByStatusAndCreatedAtBefore(
            IngestionStatus status, java.time.Instant cutoff);
}
