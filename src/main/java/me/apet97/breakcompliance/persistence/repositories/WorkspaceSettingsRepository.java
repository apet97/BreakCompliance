package me.apet97.breakcompliance.persistence.repositories;

import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceSettingsRepository extends JpaRepository<WorkspaceSettings, String> {
}
