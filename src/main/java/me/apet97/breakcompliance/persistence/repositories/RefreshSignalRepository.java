package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshSignalRepository extends JpaRepository<RefreshSignal, RefreshSignal.Pk> {

    List<RefreshSignal> findByWorkspaceIdOrderByReceivedAtDesc(String workspaceId);
}
