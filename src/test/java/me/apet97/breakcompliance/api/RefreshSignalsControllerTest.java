package me.apet97.breakcompliance.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.config.AsyncConfig;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalSource;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the /api/refresh-signals response contracts. {@code GET} lists the
 * workspace's recent signals; {@code POST /run} dispatches an
 * ingest+evaluate cycle (admin-only) asynchronously and returns
 * {@code 202 Accepted} with a run id the sidebar can poll at
 * {@code /api/ingest/runs/{id}}. The pre-2026-05-12 contract returned
 * {@code 200} with {@code findingsCount} inline; that was rewritten when
 * the synchronous body started blocking the request thread for 30s+ on
 * realistic workspace sizes.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class RefreshSignalsControllerTest {

    /**
     * Run async ingest+evaluate inline so the test's {@code @Transactional}
     * rollback still bounds the row lifecycle — the prod {@code ingestExecutor}
     * dispatches to a separate thread that wouldn't see the test's pending
     * transaction. Mirrors the {@code IngestionControllerTest} override.
     */
    @TestConfiguration
    static class SyncIngestExecutorConfig {
        @Bean(name = AsyncConfig.INGEST_EXECUTOR_BEAN)
        @Primary
        Executor syncIngestExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RefreshSignalRepository signalsRepo;

    @Autowired
    InstallationRepository installationRepo;

    @Autowired
    TokenCodec codec;

    @MockBean
    DetailedReportFetcher fetcher;

    @BeforeEach
    void cleanState() {
        signalsRepo.deleteAll();
        installationRepo.deleteAll();
        seedInstallation();
    }

    @Test
    void list_returnsSignalsSortedByReceivedAtDesc() throws Exception {
        Instant older = Instant.parse("2026-05-01T08:00:00Z");
        Instant newer = Instant.parse("2026-05-02T08:00:00Z");
        signalsRepo.save(newSignal("a", "NEW_TIME_ENTRY", older));
        signalsRepo.save(newSignal("b", "TIME_ENTRY_UPDATED", newer));

        mockMvc.perform(get("/api/refresh-signals")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("TIME_ENTRY_UPDATED"))
                .andExpect(jsonPath("$[1].eventType").value("NEW_TIME_ENTRY"));
    }

    @Test
    void list_missingToken_returns401() throws Exception {
        mockMvc.perform(get("/api/refresh-signals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void run_adminAndValidRange_returns202WithRunIdAndPollUrl() throws Exception {
        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/refresh-signals/run")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.run.id").exists())
                .andExpect(jsonPath("$.run.workspaceId").value(TestJwtForger.DEFAULT_WORKSPACE_ID))
                .andExpect(jsonPath("$.run.dateRangeStart").value("2026-05-01"))
                .andExpect(jsonPath("$.run.dateRangeEnd").value("2026-05-07"))
                .andExpect(jsonPath("$.pollUrl",
                        org.hamcrest.Matchers.startsWith("/api/ingest/runs/")))
                .andExpect(jsonPath("$.dateRangeStart").value("2026-05-01"))
                .andExpect(jsonPath("$.dateRangeEnd").value("2026-05-07"));
    }

    @Test
    void run_nonAdmin_returns403() throws Exception {
        String memberToken = TestJwtForger.forge(Map.of("workspaceRole", "MEMBER"));

        mockMvc.perform(post("/api/refresh-signals/run")
                        .header("X-Addon-Token", memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void run_invalidDateRange_returns400() throws Exception {
        mockMvc.perform(post("/api/refresh-signals/run")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-07\",\"dateRangeEnd\":\"2026-05-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void run_inactiveInstallation_returns503InstallationInactive() throws Exception {
        Installation install = installationRepo
                .findByWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID)
                .orElseThrow();
        install.setStatus(InstallationStatus.INACTIVE);
        installationRepo.save(install);

        mockMvc.perform(post("/api/refresh-signals/run")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("installation_inactive"));
    }

    private void seedInstallation() {
        Installation install = new Installation();
        install.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        install.setAddonId(TestJwtForger.DEFAULT_ADDON_ID);
        install.setAuthToken(EncryptedToken.of(codec.encrypt("seed-auth-token")));
        install.setBackendUrl(TestJwtForger.DEFAULT_BACKEND_URL);
        install.setReportsUrl("https://api.clockify.me/report");
        install.setStatus(InstallationStatus.ACTIVE);
        Instant now = Instant.now();
        install.setInstalledAt(now);
        install.setUpdatedAt(now);
        installationRepo.save(install);
    }

    private static RefreshSignal newSignal(String id, String eventType, Instant receivedAt) {
        RefreshSignal s = new RefreshSignal();
        s.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        s.setId(UUID.randomUUID().toString() + "-" + id);
        s.setSource(RefreshSignalSource.WEBHOOK);
        s.setEventType(eventType);
        s.setReceivedAt(receivedAt);
        s.setStatus(RefreshSignalStatus.PENDING);
        return s;
    }
}
