package me.apet97.breakcompliance.persistence.repositories;

import java.util.Optional;
import me.apet97.breakcompliance.persistence.entities.Installation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallationRepository extends JpaRepository<Installation, Installation.Pk> {

    Optional<Installation> findByWorkspaceId(String workspaceId);

    Optional<Installation> findByAddonId(String addonId);
}
