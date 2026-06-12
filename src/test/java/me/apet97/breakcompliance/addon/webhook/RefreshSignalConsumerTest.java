package me.apet97.breakcompliance.addon.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.clockify.HolidayFetcher;
import me.apet97.breakcompliance.clockify.TimeOffFetcher;
import me.apet97.breakcompliance.clockify.UserDirectoryFetcher;
import me.apet97.breakcompliance.config.AsyncConfig;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalSource;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.TestPropertySource;

/**
 * Locks the refresh-signal consumer behaviour: PENDING signals past the
 * debounce window get dispatched onto a fresh ingest run (single workspace
 * grouping, dateHint-driven window), and identical workspaces with an
 * in-flight run coalesce onto it instead of spawning duplicates.
 *
 * <p>The consumer is normally wired via {@code @Scheduled} but tests need
 * deterministic timing — we override
 * {@code breakcompliance.scheduling.enabled=true} (so the
 * {@code @ConditionalOnProperty} bean is created) and then call
 * {@code pollAndDispatch()} directly. Without
 * {@code @EnableScheduling}-driven firing this is the only way the method
 * runs in a test context. The {@code ingestExecutor} bean is overridden to
 * {@link SyncTaskExecutor} so the dispatched ingest+finalize+evaluate
 * cycle completes inline.
 *
 * <p>Debounce is shrunk to 100 ms here so we can seed signals that
 * immediately qualify as "drained" without test sleeps.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@TestPropertySource(properties = {
        "breakcompliance.scheduling.enabled=true",
        "breakcompliance.refresh.debounce-ms=100",
        "breakcompliance.refresh.poll-interval-ms=600000"
})
class RefreshSignalConsumerTest {

    private static final String WORKSPACE = "ws-consumer";
    private static final String ADDON = "addon-consumer";

    @TestConfiguration
    static class SyncIngestExecutorConfig {
        @Bean(name = AsyncConfig.INGEST_EXECUTOR_BEAN)
        @Primary
        Executor syncIngestExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired
    RefreshSignalConsumer consumer;

    @Autowired
    RefreshSignalRepository signals;

    @Autowired
    IngestionRunRepository runs;

    @Autowired
    InstallationRepository installations;

    @Autowired
    TokenCodec codec;

    @MockitoBean
    DetailedReportFetcher fetcher;

    @MockitoBean
    HolidayFetcher holidayFetcher;

    @MockitoBean
    TimeOffFetcher timeOffFetcher;

    @MockitoBean
    UserDirectoryFetcher userDirectoryFetcher;

    @BeforeEach
    void clean() {
        // Stub the Clockify call so executeRun's finalize commits a clean
        // empty result; we're testing dispatch + status transitions, not
        // ingest mechanics.
        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(List.of());
        Mockito.when(holidayFetcher.fetch(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
        Mockito.when(timeOffFetcher.fetchApproved(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
        Mockito.when(userDirectoryFetcher.fetchActive(anyString(), anyString(), anyString()))
                .thenReturn(Map.of());

        runs.deleteAll();
        signals.deleteAll();
        installations.deleteAll();

        Installation install = new Installation();
        install.setWorkspaceId(WORKSPACE);
        install.setAddonId(ADDON);
        install.setAuthToken(EncryptedToken.of(codec.encrypt("tok")));
        install.setBackendUrl("https://api.clockify.me/api");
        install.setReportsUrl("https://reports.api.clockify.me");
        install.setStatus(InstallationStatus.ACTIVE);
        Instant now = Instant.now();
        install.setInstalledAt(now);
        install.setUpdatedAt(now);
        installations.saveAndFlush(install);
    }

    @Test
    void pollAndDispatch_drainsPendingSignalsIntoSingleRun() throws Exception {
        seedSignal("a", "NEW_TIME_ENTRY", "2026-05-09", Instant.now().minusSeconds(5));
        seedSignal("b", "TIME_ENTRY_UPDATED", "2026-05-10", Instant.now().minusSeconds(4));
        seedSignal("c", "TIME_ENTRY_DELETED", null, Instant.now().minusSeconds(3));

        consumer.pollAndDispatch();

        // Exactly one ingest run was created for the workspace.
        List<IngestionRun> allRuns = runs.findByWorkspaceIdOrderByCreatedAtDesc(WORKSPACE);
        assertThat(allRuns).hasSize(1);
        IngestionRun run = allRuns.get(0);
        assertThat(run.getStatus()).isEqualTo(IngestionStatus.COMPLETED);

        // All three signals consumed and back-pointing to the same run.
        List<RefreshSignal> after = signals.findByWorkspaceIdOrderByReceivedAtDesc(WORKSPACE);
        assertThat(after).hasSize(3);
        assertThat(after).allMatch(s -> s.getStatus() == RefreshSignalStatus.CONSUMED);
        assertThat(after).allMatch(s -> run.getId().equals(s.getIngestionRunId()));
    }

    @Test
    void pollAndDispatch_freshSignalsBelowDebounceWindowAreSkipped() {
        // Signal received "just now" — within the 100 ms debounce window.
        seedSignal("fresh", "NEW_TIME_ENTRY", "2026-05-10", Instant.now());

        consumer.pollAndDispatch();

        // No run dispatched, signal stays PENDING for the next tick.
        assertThat(runs.count()).isZero();
        RefreshSignal s = signals.findByWorkspaceIdOrderByReceivedAtDesc(WORKSPACE).get(0);
        assertThat(s.getStatus()).isEqualTo(RefreshSignalStatus.PENDING);
    }

    @Test
    void pollAndDispatch_coalescesOntoInflightRunForSameWindow() {
        // Seed an in-flight RUNNING run that already covers tomorrow's
        // window. Subsequent signals for the same window must coalesce
        // onto it instead of spawning a duplicate ingest.
        IngestionRun inflight = new IngestionRun();
        inflight.setWorkspaceId(WORKSPACE);
        inflight.setId(UUID.randomUUID().toString());
        inflight.setDateRangeStart("2026-05-10");
        inflight.setDateRangeEnd("2026-05-10");
        inflight.setStatus(IngestionStatus.RUNNING);
        inflight.setEntriesProcessed(0);
        Instant now = Instant.now();
        inflight.setCreatedAt(now);
        inflight.setCompletedAt(now);
        runs.saveAndFlush(inflight);

        seedSignal("dup", "NEW_TIME_ENTRY", "2026-05-10", Instant.now().minusSeconds(2));

        consumer.pollAndDispatch();

        // Still exactly one run — the in-flight one — and the signal is
        // back-pointed to it as COALESCED.
        assertThat(runs.count()).isEqualTo(1);
        RefreshSignal s = signals.findByWorkspaceIdOrderByReceivedAtDesc(WORKSPACE).get(0);
        assertThat(s.getStatus()).isEqualTo(RefreshSignalStatus.COALESCED);
        assertThat(s.getIngestionRunId()).isEqualTo(inflight.getId());
    }

    private void seedSignal(String idSuffix, String eventType, String dateHint, Instant receivedAt) {
        RefreshSignal s = new RefreshSignal();
        s.setWorkspaceId(WORKSPACE);
        s.setId(UUID.randomUUID().toString() + "-" + idSuffix);
        s.setSource(RefreshSignalSource.WEBHOOK);
        s.setEventType(eventType);
        s.setDateHint(dateHint);
        s.setReceivedAt(receivedAt);
        s.setStatus(RefreshSignalStatus.PENDING);
        signals.saveAndFlush(s);
    }
}
