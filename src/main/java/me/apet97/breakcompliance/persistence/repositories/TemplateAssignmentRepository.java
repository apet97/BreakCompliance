package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateAssignmentRepository extends JpaRepository<TemplateAssignment, TemplateAssignment.Pk> {

    List<TemplateAssignment> findByWorkspaceId(String workspaceId);

    List<TemplateAssignment> findByWorkspaceIdAndTemplateId(String workspaceId, String templateId);
}
