package me.apet97.breakcompliance.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the /api/session response contract that sidebar.js reads on boot:
 * {@code workspaceId}, {@code userId}, {@code workspaceRole}, {@code addonId},
 * and {@code appliedPresetKey} (best-effort — null if no settings row yet).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class SessionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WorkspaceSettingsRepository settingsRepo;

    @BeforeEach
    void cleanState() {
        settingsRepo.deleteAll();
    }

    @Test
    void session_withClaims_returnsClaimEcho() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/session").header("X-Addon-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(TestJwtForger.DEFAULT_WORKSPACE_ID))
                .andExpect(jsonPath("$.addonId").value(TestJwtForger.DEFAULT_ADDON_ID))
                .andExpect(jsonPath("$.workspaceRole").value("ADMIN"));
    }

    @Test
    void session_noSettingsRow_appliedPresetKeyIsNull() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/session").header("X-Addon-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(TestJwtForger.DEFAULT_WORKSPACE_ID))
                // appliedPresetKey is serialized as JSON null when no settings
                // row exists. The sidebar's `?? "custom-basic"` fallback then
                // renders the default label.
                .andExpect(jsonPath("$.appliedPresetKey").value(nullValue()));
    }

    @Test
    void session_withSettingsRow_returnsAppliedPresetKey() throws Exception {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(false);
        s.setAppliedPresetKey("germany-arbzg-style");
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        settingsRepo.save(s);

        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/session").header("X-Addon-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedPresetKey").value("germany-arbzg-style"));
    }

    @Test
    void session_missingToken_returns401() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void session_productionBackend_returnsAppClockifyMeSettingsUrl() throws Exception {
        // DEFAULT_BACKEND_URL is "https://api.clockify.me/api" — production-shaped.
        // settingsUrl resolves to the in-app Workspace Settings page using the
        // manifest key as the addon slug.
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/session").header("X-Addon-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsUrl")
                        .value("https://app.clockify.me/workspaces/"
                                + TestJwtForger.DEFAULT_WORKSPACE_ID
                                + "/settings/addons/" + TestJwtForger.MANIFEST_KEY));
    }

    @Test
    void session_devPortalBackend_returnsDeveloperClockifyMeSettingsUrl() throws Exception {
        // When the JWT's backendUrl points at the dev portal, the sidebar's
        // Settings button needs to open the per-installation page on
        // developer.clockify.me using the JWT's addonId claim (which IS
        // the per-installation id on the dev portal).
        String token = TestJwtForger.forge(java.util.Map.of(
                "backendUrl", "https://developer.clockify.me/api/v1"));
        mockMvc.perform(get("/api/session").header("X-Addon-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsUrl")
                        .value("https://developer.clockify.me/addon/"
                                + TestJwtForger.DEFAULT_ADDON_ID + "/settings"));
    }
}
