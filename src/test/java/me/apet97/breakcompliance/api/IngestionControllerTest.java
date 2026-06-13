package me.apet97.breakcompliance.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import jakarta.persistence.EntityManager;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.clockify.ClockifyApiException;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.clockify.HolidayFetcher;
import me.apet97.breakcompliance.clockify.TimeOffFetcher;
import me.apet97.breakcompliance.clockify.UserDirectoryFetcher;
import me.apet97.breakcompliance.config.AsyncConfig;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the ingestion API's new async contract: POST returns 202 after the
 * prepare phase commits the {@code IngestionRun} row in RUNNING state, the
 * fetch + finalize executes on the {@code ingestExecutor} pool, and the
 * sidebar polls {@code GET /api/ingest/runs/{id}} for the terminal state.
 *
 * <p>The test overrides {@code ingestExecutor} with a {@link SyncTaskExecutor}
 * so the async work runs inline in the test thread / transaction — that
 * way the existing {@code @Transactional} rollback semantics keep working
 * and we still exercise the same prepare → execute → finalize sequence.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class IngestionControllerTest {

    /**
     * Run async ingest work inline so test {@code @Transactional} semantics
     * still apply (the prod {@code ingestExecutor} dispatches to a separate
     * thread that wouldn't see the test's pending tx). The bean override
     * here replaces the {@link AsyncConfig#INGEST_EXECUTOR_BEAN} named bean.
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
    InstallationRepository installationRepo;

    @Autowired
    IngestionRunRepository runRepo;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TimeEntryRepository timeEntryRepo;

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
    void cleanState() {
        Mockito.when(holidayFetcher.fetch(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
        Mockito.when(timeOffFetcher.fetchApproved(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
        Mockito.when(userDirectoryFetcher.fetchActive(anyString(), anyString(), anyString()))
                .thenReturn(Map.of());
        timeEntryRepo.deleteAll();
        runRepo.deleteAll();
        installationRepo.deleteAll();
        seedInstallation();
    }

    @Test
    void detailedReport_returnsAcceptedAndPersistsRun() throws Exception {
        // P1.3 added a 6-arg overload that takes the excludeUnsubmittedEntries
        // flag; IngestionService now always calls the 6-arg variant, so the
        // stub must match its signature.
        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.run.id").isNotEmpty())
                // With the sync executor the async work finishes before the
                // response is rendered; status will already be COMPLETED.
                .andExpect(jsonPath("$.run.status").value("COMPLETED"));

        // Sync executor → fetch + finalize ran inline; the run row is COMPLETED.
        List<IngestionRun> runs = runRepo.findAll();
        org.assertj.core.api.Assertions.assertThat(runs).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(runs.get(0).getStatus())
                .isEqualTo(IngestionStatus.COMPLETED);
    }

    @Test
    void detailedReport_emptyCurrentReportRemovesCachedEntriesInRange() throws Exception {
        TimeEntry stale = cachedEntry("deleted-entry-1", "2026-05-03T09:00:00Z", "2026-05-03T14:00:00Z");
        timeEntryRepo.saveAndFlush(stale);

        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.run.status").value("COMPLETED"));

        org.assertj.core.api.Assertions.assertThat(timeEntryRepo.findByWorkspaceIdAndStartAtBetween(
                        TestJwtForger.DEFAULT_WORKSPACE_ID,
                        Instant.parse("2026-05-01T00:00:00Z"),
                        Instant.parse("2026-05-08T00:00:00Z")))
                .isEmpty();
    }

    @Test
    void detailedReport_reconcileLeavesCachedEntriesOutsideRange() throws Exception {
        timeEntryRepo.save(cachedEntry("inside-entry", "2026-05-03T09:00:00Z", "2026-05-03T14:00:00Z"));
        timeEntryRepo.saveAndFlush(cachedEntry("outside-entry", "2026-04-30T09:00:00Z", "2026-04-30T14:00:00Z"));

        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.run.status").value("COMPLETED"));

        entityManager.clear();

        org.assertj.core.api.Assertions.assertThat(timeEntryRepo.findById(
                        new TimeEntry.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, "outside-entry")))
                .isPresent();
        org.assertj.core.api.Assertions.assertThat(timeEntryRepo.findById(
                        new TimeEntry.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, "inside-entry")))
                .isEmpty();
    }

    @Test
    void detailedReport_clockifyReports401_recordsFailedRunWithoutPropagating() throws Exception {
        // P1.3 added a 6-arg overload that takes the excludeUnsubmittedEntries
        // flag; IngestionService now always calls the 6-arg variant, so the
        // stub must match its signature.
        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenThrow(new ClockifyApiException("Clockify client error: 401", 401));

        // The controller no longer translates 401 into a synchronous 503 — the
        // request returns 202 immediately. The sidebar polls /api/ingest/runs
        // and discovers the FAILED row, then renders the user-friendly
        // "reports unavailable" message from the errorCode.
        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isAccepted());

        List<IngestionRun> runs = runRepo.findAll();
        org.assertj.core.api.Assertions.assertThat(runs).hasSize(1);
        IngestionRun run = runs.get(0);
        org.assertj.core.api.Assertions.assertThat(run.getStatus()).isEqualTo(IngestionStatus.FAILED);
        org.assertj.core.api.Assertions.assertThat(run.getErrorCode()).isEqualTo("ClockifyApi:401");
    }

    @Test
    void detailedReport_inactiveInstallation_returns503InstallationInactive() throws Exception {
        // Flip the seeded installation to INACTIVE — simulates a STATUS_CHANGED
        // lifecycle delivery that disabled the addon for this workspace. The
        // guard fires during prepareRun (synchronous), so the controller can
        // still surface a synchronous 503 here.
        Installation install = installationRepo
                .findByWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID)
                .orElseThrow();
        install.setStatus(InstallationStatus.INACTIVE);
        installationRepo.save(install);

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("installation_inactive"))
                .andExpect(jsonPath("$.message").exists());

        // No run should be persisted — the guard fires before any DB write.
        org.assertj.core.api.Assertions.assertThat(runRepo.findAll()).isEmpty();
    }

    @Test
    void detailedReport_inflightRunForSameRange_returns409WithExistingRunId() throws Exception {
        // Seed a RUNNING run for the workspace + date range the next
        // request will ask for. The controller's dedupe must surface a
        // 409 with a pointer to the existing run instead of spawning a
        // second ingest.
        IngestionRun inflight = new IngestionRun();
        inflight.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        inflight.setId("inflight-run-1");
        inflight.setDateRangeStart("2026-05-01");
        inflight.setDateRangeEnd("2026-05-07");
        inflight.setStatus(IngestionStatus.RUNNING);
        inflight.setEntriesProcessed(0);
        Instant now = Instant.now();
        inflight.setCreatedAt(now);
        inflight.setCompletedAt(now);
        runRepo.saveAndFlush(inflight);

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ingest_in_progress"))
                .andExpect(jsonPath("$.existingRunId").value("inflight-run-1"))
                .andExpect(jsonPath("$.pollUrl").value("/api/ingest/runs/inflight-run-1"));

        // Still exactly one run — the seeded one.
        org.assertj.core.api.Assertions.assertThat(runRepo.count()).isEqualTo(1);
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

    private static TimeEntry cachedEntry(String sourceEntryId, String startIso, String endIso) {
        Instant start = Instant.parse(startIso);
        Instant end = Instant.parse(endIso);
        TimeEntry entry = new TimeEntry();
        entry.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        entry.setSourceEntryId(sourceEntryId);
        entry.setUserId("user-a");
        entry.setUserName("User A");
        entry.setStartAt(start);
        entry.setEndAt(end);
        entry.setDurationSeconds(java.time.Duration.between(start, end).toSeconds());
        entry.setBillable(false);
        entry.setTags(List.of());
        entry.setRaw(Map.of("type", "REGULAR"));
        entry.setIngestedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return entry;
    }
}
