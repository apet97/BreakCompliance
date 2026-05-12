package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import java.util.Optional;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, IngestionRun.Pk> {

    List<IngestionRun> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

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
}
