package me.apet97.breakcompliance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the sidebar-facing preset chooser API:
 * <ul>
 *   <li>{@code GET /api/presets} returns the three built-in presets with
 *       their canonical thresholds so the sidebar can render preview cards.</li>
 *   <li>{@code POST /api/presets/apply} updates the workspace's settings
 *       row with the preset's threshold values, records
 *       {@code appliedPresetKey}, and is admin-gated.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class PresetControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WorkspaceSettingsRepository settingsRepo;

    @BeforeEach
    void seed() {
        settingsRepo.deleteAll();
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(false);
        s.setAppliedPresetKey("custom-basic");
        s.setCustomWorkThresholdMinutes(240);
        s.setCustomBreakThresholdMinutes(15);
        s.setCustomMinBreakSegmentMinutes(5);
        s.setCustomMaxContinuousWorkMinutes(240);
        s.setCustomGracePeriodMinutes(5);
        s.setCustomAllowSplitBreaks(true);
        s.setCustomSecondWorkThresholdMinutes(0);
        s.setCustomSecondBreakThresholdMinutes(0);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        settingsRepo.saveAndFlush(s);
    }

    @Test
    void listPresets_returnsAllThreeWithThresholdsAndLabels() throws Exception {
        mockMvc.perform(get("/api/presets")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presets[0].key").value("custom-basic"))
                .andExpect(jsonPath("$.presets[0].label").value("Custom (Editable Defaults)"))
                .andExpect(jsonPath("$.presets[0].thresholds.workThresholdMinutes").value(240))
                .andExpect(jsonPath("$.presets[1].key").value("california-style"))
                .andExpect(jsonPath("$.presets[1].label").value("California (IWC Meal & Rest)"))
                .andExpect(jsonPath("$.presets[1].thresholds.workThresholdMinutes").value(300))
                .andExpect(jsonPath("$.presets[1].thresholds.allowSplitBreaks").value(false))
                .andExpect(jsonPath("$.presets[2].key").value("germany-arbzg-style"))
                .andExpect(jsonPath("$.presets[2].thresholds.secondWorkThresholdMinutes").value(540));
    }

    @Test
    void applyPreset_overwritesThresholdsAndPersistsAppliedKey() throws Exception {
        mockMvc.perform(post("/api/presets/apply")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presetKey\":\"germany-arbzg-style\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedPresetKey").value("germany-arbzg-style"))
                .andExpect(jsonPath("$.workThresholdMinutes").value(360))
                .andExpect(jsonPath("$.secondWorkThresholdMinutes").value(540));

        WorkspaceSettings updated = settingsRepo.findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThatThresholds(updated, 360, 30, 15, 360, 5, true, 540, 45);
        assertEqual(updated.getAppliedPresetKey(), "germany-arbzg-style");
    }

    @Test
    void applyPreset_unknownKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/presets/apply")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presetKey\":\"narnia\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown_preset_key"));
    }

    @Test
    void applyPreset_missingKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/presets/apply")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_preset_key"));
    }

    @Test
    void applyPreset_byNonAdmin_isForbidden() throws Exception {
        String memberToken = TestJwtForger.forge(java.util.Map.of("workspaceRole", "MEMBER"));
        mockMvc.perform(post("/api/presets/apply")
                        .header("X-Addon-Token", memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presetKey\":\"germany-arbzg-style\"}"))
                .andExpect(status().isForbidden());
    }

    private static void assertThatThresholds(WorkspaceSettings s,
            int work, int req, int segment, int maxContinuous, int grace, boolean split,
            int secondWork, int secondReq) {
        org.assertj.core.api.Assertions.assertThat(s.getCustomWorkThresholdMinutes()).isEqualTo(work);
        org.assertj.core.api.Assertions.assertThat(s.getCustomBreakThresholdMinutes()).isEqualTo(req);
        org.assertj.core.api.Assertions.assertThat(s.getCustomMinBreakSegmentMinutes()).isEqualTo(segment);
        org.assertj.core.api.Assertions.assertThat(s.getCustomMaxContinuousWorkMinutes()).isEqualTo(maxContinuous);
        org.assertj.core.api.Assertions.assertThat(s.getCustomGracePeriodMinutes()).isEqualTo(grace);
        org.assertj.core.api.Assertions.assertThat(s.getCustomAllowSplitBreaks()).isEqualTo(split);
        org.assertj.core.api.Assertions.assertThat(s.getCustomSecondWorkThresholdMinutes()).isEqualTo(secondWork);
        org.assertj.core.api.Assertions.assertThat(s.getCustomSecondBreakThresholdMinutes()).isEqualTo(secondReq);
    }

    private static void assertEqual(Object actual, Object expected) {
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }
}
