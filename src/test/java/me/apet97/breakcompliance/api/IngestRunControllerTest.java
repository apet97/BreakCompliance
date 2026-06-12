package me.apet97.breakcompliance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the polling endpoint the sidebar hits after starting an ingest.
 * Confirms (a) status + entries are surfaced, (b) cross-workspace lookups
 * return 404 even when the id is known elsewhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class IngestRunControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    IngestionRunRepository runRepo;

    @BeforeEach
    void clean() {
        runRepo.deleteAll();
    }

    @Test
    void getRun_returnsTerminalStateAndEntries() throws Exception {
        IngestionRun run = seedRun(
                TestJwtForger.DEFAULT_WORKSPACE_ID,
                IngestionStatus.COMPLETED,
                1234L,
                null);

        mockMvc.perform(get("/api/ingest/runs/" + run.getId())
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(run.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.entriesProcessed").value(1234));
    }

    @Test
    void getRun_failedRunSurfacesErrorCode() throws Exception {
        IngestionRun run = seedRun(
                TestJwtForger.DEFAULT_WORKSPACE_ID,
                IngestionStatus.FAILED,
                0L,
                "ClockifyApi:401");

        mockMvc.perform(get("/api/ingest/runs/" + run.getId())
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ClockifyApi:401"));
    }

    @Test
    void getLatest_returnsMostRecentCompletedRun() throws Exception {
        Instant base = Instant.parse("2026-05-10T12:00:00Z");
        seedRun(TestJwtForger.DEFAULT_WORKSPACE_ID, IngestionStatus.COMPLETED, 100L, null, base);
        IngestionRun newer = seedRun(TestJwtForger.DEFAULT_WORKSPACE_ID, IngestionStatus.COMPLETED, 500L, null, base.plusSeconds(3600));
        // RUNNING + FAILED rows must not shadow the COMPLETED winner — the
        // sidebar would otherwise see a stale "Refreshed" timestamp.
        seedRun(TestJwtForger.DEFAULT_WORKSPACE_ID, IngestionStatus.RUNNING, 9L, null, base.plusSeconds(7200));
        seedRun(TestJwtForger.DEFAULT_WORKSPACE_ID, IngestionStatus.FAILED, 0L, "ClockifyApi:401", base.plusSeconds(10800));

        mockMvc.perform(get("/api/ingest/runs/latest")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(newer.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.entriesProcessed").value(500));
    }

    @Test
    void getLatest_returnsNoContentWhenNoCompletedRun() throws Exception {
        // Only a FAILED run exists — endpoint must not return that one.
        seedRun(TestJwtForger.DEFAULT_WORKSPACE_ID, IngestionStatus.FAILED, 0L, "ClockifyApi:401");

        mockMvc.perform(get("/api/ingest/runs/latest")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void getLatest_ignoresOtherWorkspaces() throws Exception {
        seedRun("ws-other", IngestionStatus.COMPLETED, 999L, null);

        mockMvc.perform(get("/api/ingest/runs/latest")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void getRun_returnsNotFoundForOtherWorkspaceRun() throws Exception {
        IngestionRun run = seedRun(
                "ws-other",
                IngestionStatus.COMPLETED,
                0L,
                null);

        mockMvc.perform(get("/api/ingest/runs/" + run.getId())
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isNotFound());
    }

    private IngestionRun seedRun(String workspaceId, IngestionStatus status, long entries, String errorCode) {
        return seedRun(workspaceId, status, entries, errorCode, Instant.now());
    }

    private IngestionRun seedRun(String workspaceId, IngestionStatus status, long entries, String errorCode, Instant completedAt) {
        IngestionRun run = new IngestionRun();
        run.setWorkspaceId(workspaceId);
        run.setId(UUID.randomUUID().toString());
        run.setDateRangeStart("2026-05-01");
        run.setDateRangeEnd("2026-05-07");
        run.setStatus(status);
        run.setEntriesProcessed(entries);
        run.setErrorCode(errorCode);
        run.setCreatedAt(completedAt);
        run.setCompletedAt(completedAt);
        return runRepo.saveAndFlush(run);
    }
}
