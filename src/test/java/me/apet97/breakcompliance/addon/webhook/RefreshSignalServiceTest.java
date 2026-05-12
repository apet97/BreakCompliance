package me.apet97.breakcompliance.addon.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalSource;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the contract that webhook-driven refresh signals are recorded
 * idempotently and with the documented (source=WEBHOOK, status=PENDING)
 * shape. The runner that later acknowledges them is out of scope.
 */
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
@Transactional
class RefreshSignalServiceTest {

    private static final String WS = "ws-refresh";

    @Autowired
    RefreshSignalService service;

    @Autowired
    RefreshSignalRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    @Test
    void recordWebhookSignal_persistsPendingSignal() {
        RefreshSignal s = service.recordWebhookSignal(WS, "NEW_TIME_ENTRY");

        assertThat(s.getId()).isNotBlank();
        assertThat(s.getWorkspaceId()).isEqualTo(WS);
        assertThat(s.getSource()).isEqualTo(RefreshSignalSource.WEBHOOK);
        assertThat(s.getStatus()).isEqualTo(RefreshSignalStatus.PENDING);
        assertThat(s.getEventType()).isEqualTo("NEW_TIME_ENTRY");
        assertThat(s.getReceivedAt()).isNotNull();
        // Detail fields default null when caller didn't supply them —
        // Detailed Report stays the source of truth for the actual entry.
        assertThat(s.getEntityId()).isNull();
        assertThat(s.getDateHint()).isNull();

        assertThat(repo.findByWorkspaceIdOrderByReceivedAtDesc(WS)).hasSize(1);
    }

    @Test
    void recordWebhookSignal_persistsDateHintWhenSupplied() {
        // The consumer in Phase 2 reads dateHint to coalesce signals to the
        // smallest covering re-ingest window. A null hint still records
        // the signal; the consumer falls back to a default window.
        RefreshSignal s = service.recordWebhookSignal(WS, "NEW_TIME_ENTRY", "2026-05-09");

        assertThat(s.getDateHint()).isEqualTo("2026-05-09");
    }

    @Test
    void recordWebhookSignal_idsAreUnique() {
        RefreshSignal a = service.recordWebhookSignal(WS, "NEW_TIME_ENTRY");
        RefreshSignal b = service.recordWebhookSignal(WS, "TIME_ENTRY_UPDATED");

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(repo.findByWorkspaceIdOrderByReceivedAtDesc(WS)).hasSize(2);
    }

    @Test
    void recordWebhookSignal_recordsAcrossWorkspaces() {
        service.recordWebhookSignal("ws-A", "NEW_TIME_ENTRY");
        service.recordWebhookSignal("ws-B", "NEW_TIME_ENTRY");

        assertThat(repo.findByWorkspaceIdOrderByReceivedAtDesc("ws-A")).hasSize(1);
        assertThat(repo.findByWorkspaceIdOrderByReceivedAtDesc("ws-B")).hasSize(1);
    }
}
