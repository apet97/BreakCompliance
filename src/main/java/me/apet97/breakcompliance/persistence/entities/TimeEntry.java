package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "breakcompliance_time_entries")
@IdClass(TimeEntry.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TimeEntry {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "source_entry_id", nullable = false)
    private String sourceEntryId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "description")
    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "billable")
    private Boolean billable;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> raw;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String sourceEntryId;
    }
}
