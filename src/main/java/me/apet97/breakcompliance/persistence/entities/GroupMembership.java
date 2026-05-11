package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "breakcompliance_group_memberships")
@IdClass(GroupMembership.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GroupMembership {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String groupId;
        private String userId;
    }
}
