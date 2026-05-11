package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, IngestionRun.Pk> {

    List<IngestionRun> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
