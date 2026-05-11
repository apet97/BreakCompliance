package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import java.util.Optional;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleTemplateRepository extends JpaRepository<RuleTemplate, RuleTemplate.Pk> {

    List<RuleTemplate> findByWorkspaceId(String workspaceId);

    Optional<RuleTemplate> findByWorkspaceIdAndKey(String workspaceId, String key);
}
