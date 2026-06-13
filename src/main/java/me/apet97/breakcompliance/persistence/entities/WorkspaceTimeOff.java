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

/**
 * P1.2 — cached approved time-off rows populated by {@code TimeOffFetcher}.
 * Evaluation converts these rows into synthetic {@code TIME_OFF} entries
 * clipped to the requested date range.
 *
 * <p>Stored verbatim from the Clockify response so a workspace with
 * partial-day requests (rare but possible) doesn't lose precision via a
 * date-only round-trip.
 */
@Entity
@Table(name = "breakcompliance_workspace_time_off")
@IdClass(WorkspaceTimeOff.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceTimeOff {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String sourceId;
    }
}
