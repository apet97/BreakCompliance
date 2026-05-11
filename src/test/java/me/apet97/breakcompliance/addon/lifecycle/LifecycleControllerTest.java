package me.apet97.breakcompliance.addon.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.WebhookAuthTokenRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class LifecycleControllerTest {

    private static final String INSTALLED_PAYLOAD = """
            {
              "addonId": "69e81390556e8f94308aaad8",
              "workspaceId": "ws-test",
              "authToken": "install-secret-token",
              "asUser": "user-1",
              "apiUrl": "https://api.clockify.me/api",
              "webhooks": [
                {"path": "https://addon.example.com//webhook/new-time-entry",
                 "event": "NEW_TIME_ENTRY",
                 "authToken": "wh-token-1"}
              ]
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InstallationRepository installationRepo;

    @Autowired
    WebhookAuthTokenRepository webhookRepo;

    @Autowired
    WorkspaceSettingsRepository settingsRepo;

    @Autowired
    TokenCodec codec;

    @BeforeEach
    void cleanState() {
        webhookRepo.deleteAll();
        installationRepo.deleteAll();
        settingsRepo.deleteAll();
    }

    @Test
    void installed_happyPath_persistsInstallationAndWebhookTokenWithNormalizedPath() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isOk());

        Installation install = installationRepo
                .findById(new Installation.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, TestJwtForger.DEFAULT_ADDON_ID))
                .orElseThrow();
        assertThat(install.getStatus()).isEqualTo(InstallationStatus.ACTIVE);
        assertThat(install.getBackendUrl()).isEqualTo(TestJwtForger.DEFAULT_BACKEND_URL);
        // JWT's 'user' claim is unset, but the body's 'asUser' is read as fallback via
        // ClaimsNormalizer.enrichFromInstalledPayload — see INSTALLED payload spec.
        assertThat(install.getInstallerUserId()).isEqualTo("user-1");
        String decryptedInstall = codec.decrypt(install.getAuthToken().getKeyId(), install.getAuthToken().getCipher());
        assertThat(decryptedInstall).isEqualTo("install-secret-token");

        WebhookAuthToken wat = webhookRepo
                .findByWorkspaceIdAndAddonIdAndPath(
                        TestJwtForger.DEFAULT_WORKSPACE_ID,
                        TestJwtForger.DEFAULT_ADDON_ID,
                        "/webhook/new-time-entry")
                .orElseThrow();
        assertThat(wat.getEventType()).isEqualTo("NEW_TIME_ENTRY");
        String decryptedWh = codec.decrypt(wat.getAuthToken().getKeyId(), wat.getAuthToken().getCipher());
        assertThat(decryptedWh).isEqualTo("wh-token-1");

        assertThat(settingsRepo.findById(TestJwtForger.DEFAULT_WORKSPACE_ID)).isPresent();
    }

    @Test
    void installed_isIdempotentOnRetry() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/lifecycle/installed")
                            .header("X-Addon-Lifecycle-Token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(INSTALLED_PAYLOAD))
                    .andExpect(status().isOk());
        }
        assertThat(installationRepo.count()).isEqualTo(1);
        assertThat(webhookRepo.count()).isEqualTo(1);
    }

    @Test
    void installed_missingToken_returns401() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isUnauthorized());
        assertThat(installationRepo.count()).isZero();
    }

    @Test
    void installed_tamperedSignature_returns401() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", tampered)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void installed_expiredToken_returns401() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        String token = TestJwtForger.forge(Map.of(), past, past.plus(Duration.ofMinutes(1)));

        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void installed_missingExp_returns401() throws Exception {
        String token = TestJwtForger.forgeWithoutExp(Map.of());

        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void installed_missingWorkspaceIdAfterAliasNormalization_returns401() throws Exception {
        // Forge a token with neither workspaceId nor activeWs
        Map<String, Object> overrides = new java.util.HashMap<>();
        overrides.put("workspaceId", null);
        String token = TestJwtForger.forge(overrides);

        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleted_removesInstallationAndCascadesWebhookTokens() throws Exception {
        // First install
        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isOk());
        assertThat(installationRepo.count()).isEqualTo(1);
        assertThat(webhookRepo.count()).isEqualTo(1);

        // Then delete
        mockMvc.perform(post("/lifecycle/deleted")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addonId\":\"69e81390556e8f94308aaad8\",\"workspaceId\":\"ws-test\"}"))
                .andExpect(status().isOk());

        assertThat(installationRepo.count()).isZero();
        assertThat(webhookRepo.count()).isZero();
    }

    @Test
    void statusChanged_updatesInstallationStatus() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isOk());

        mockMvc.perform(post("/lifecycle/status-changed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        Installation install = installationRepo
                .findById(new Installation.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, TestJwtForger.DEFAULT_ADDON_ID))
                .orElseThrow();
        assertThat(install.getStatus()).isEqualTo(InstallationStatus.INACTIVE);
    }

    @Test
    void settingsUpdated_acks200WithoutMutation() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isOk());

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void installed_devPortalShape_backendUrlComesFromBodyApiUrlWhenJwtLacksIt() throws Exception {
        // Dev-portal lifecycle JWT carries only the bare minimum claims —
        // workspaceId, addonId, type, iss, sub, exp. It does NOT include
        // backendUrl. The body still has apiUrl, which our enrichment must
        // pull through so the Installation row's backend_url is populated.
        Map<String, Object> overrides = new java.util.HashMap<>();
        overrides.put("backendUrl", null); // strip the default
        String tokenWithoutBackendUrl = TestJwtForger.forge(overrides);

        String payload = """
                {
                  "addonId": "69e81390556e8f94308aaad8",
                  "workspaceId": "ws-test",
                  "authToken": "install-secret-token",
                  "asUser": "user-from-body",
                  "apiUrl": "https://api.clockify.me",
                  "addonUserId": "addon-user-from-body",
                  "webhooks": []
                }
                """;

        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", tokenWithoutBackendUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        Installation install = installationRepo
                .findById(new Installation.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, TestJwtForger.DEFAULT_ADDON_ID))
                .orElseThrow();
        // body.apiUrl was "https://api.clockify.me" (no /api suffix); enrichment
        // applies the same trailing-/api normalization as the JWT path
        assertThat(install.getBackendUrl()).isEqualTo("https://api.clockify.me/api");
        // body.asUser preferred over body.addonUserId
        assertThat(install.getInstallerUserId()).isEqualTo("user-from-body");
    }

    @Test
    void absoluteWebhookPathsAreNormalizedOnInstall() throws Exception {
        String payload = """
                {
                  "addonId": "69e81390556e8f94308aaad8",
                  "workspaceId": "ws-test",
                  "authToken": "tok",
                  "webhooks": [
                    {"path": "https://addon.example.com//deep//path//webhook", "event": "NEW_TIME_ENTRY", "authToken": "x"}
                  ]
                }
                """;

        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        List<WebhookAuthToken> all = webhookRepo.findByWorkspaceIdAndAddonId(
                TestJwtForger.DEFAULT_WORKSPACE_ID, TestJwtForger.DEFAULT_ADDON_ID);
        assertThat(all).extracting(WebhookAuthToken::getPath)
                .containsExactly("/deep/path/webhook");
    }
}
