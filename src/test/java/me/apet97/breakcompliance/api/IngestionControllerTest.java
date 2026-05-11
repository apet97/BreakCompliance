package me.apet97.breakcompliance.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.clockify.ClockifyApiException;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the ingestion API's behavior when the Clockify reports endpoint
 * returns 401 — the dev-portal limitation that the user-visible "Check
 * Compliance" flow has to render gracefully.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class IngestionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InstallationRepository installationRepo;

    @Autowired
    IngestionRunRepository runRepo;

    @Autowired
    TokenCodec codec;

    @MockBean
    DetailedReportFetcher fetcher;

    @BeforeEach
    void cleanState() {
        runRepo.deleteAll();
        installationRepo.deleteAll();
        seedInstallation();
    }

    @Test
    void detailedReport_clockifyReports401_returns503ReportsUnavailable() throws Exception {
        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new ClockifyApiException("Clockify client error: 401", 401));

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("reports_unavailable"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void detailedReport_clockifyReports401_recordsFailedRun() throws Exception {
        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new ClockifyApiException("Clockify client error: 401", 401));

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isServiceUnavailable());

        // The failed run must still be persisted for admin audit.
        List<IngestionRun> runs = runRepo.findAll();
        assertThatRunIsFailedWithCode(runs, "ClockifyApi:401");
    }

    @Test
    void detailedReport_happyPath_returnsWrappedRun() throws Exception {
        Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/ingest/detailed-report")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("COMPLETED"))
                .andExpect(jsonPath("$.run.entriesProcessed").value(0));
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

    private static void assertThatRunIsFailedWithCode(List<IngestionRun> runs, String errorCode) {
        org.assertj.core.api.Assertions.assertThat(runs).hasSize(1);
        IngestionRun run = runs.get(0);
        org.assertj.core.api.Assertions.assertThat(run.getStatus()).isEqualTo(IngestionStatus.FAILED);
        org.assertj.core.api.Assertions.assertThat(run.getErrorCode()).isEqualTo(errorCode);
    }
}
