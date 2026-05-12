package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "breakcompliance_refresh_signals")
@IdClass(RefreshSignal.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RefreshSignal {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RefreshSignalSource source;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "date_hint")
    private String dateHint;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RefreshSignalStatus status;

    /**
     * Back-pointer to the {@link IngestionRun} that consumed (or coalesced)
     * this signal. Null for PENDING rows, populated by the consumer when
     * it CLAIMs a signal. Nullable in the schema (V10) — pre-V10 rows in
     * ACKNOWLEDGED carry no run reference.
     */
    @Column(name = "ingestion_run_id")
    private String ingestionRunId;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String id;
    }
}
