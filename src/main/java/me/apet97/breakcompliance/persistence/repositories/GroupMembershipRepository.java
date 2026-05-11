package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import me.apet97.breakcompliance.persistence.entities.GroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, GroupMembership.Pk> {

    List<GroupMembership> findByWorkspaceIdAndUserId(String workspaceId, String userId);

    List<GroupMembership> findByWorkspaceIdAndGroupId(String workspaceId, String groupId);
}
