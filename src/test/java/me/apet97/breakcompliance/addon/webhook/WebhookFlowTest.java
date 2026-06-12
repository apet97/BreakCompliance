package me.apet97.breakcompliance.addon.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.RedisTestcontainersConfig;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import me.apet97.breakcompliance.persistence.repositories.WebhookAuthTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
    PostgresTestcontainersConfig.class,
    RedisTestcontainersConfig.class,
    TestClockifyKeyConfig.class
})
class WebhookFlowTest {

    private static final String WORKSPACE = "ws-wh";
    private static final String ADDON = "addon-wh-001";
    private static final String PATH = "/webhook/new-time-entry";
    private static final String STORED_AUTH_TOKEN = "webhook-secret-001";
    private static final String EVENT_TYPE = "NEW_TIME_ENTRY";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InstallationRepository installationRepo;

    @Autowired
    WebhookAuthTokenRepository webhookRepo;

    @Autowired
    RefreshSignalRepository signalRepo;

    @Autowired
    TokenCodec codec;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void seedInstallationAndWebhookToken() {
        signalRepo.deleteAll();
        webhookRepo.deleteAll();
        installationRepo.deleteAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        Installation install = new Installation();
        install.setWorkspaceId(WORKSPACE);
        install.setAddonId(ADDON);
        install.setAuthToken(EncryptedToken.of(codec.encrypt("install-tok")));
        install.setBackendUrl("https://api.clockify.me/api");
        install.setStatus(InstallationStatus.ACTIVE);
        install.setInstalledAt(Instant.now());
        install.setUpdatedAt(Instant.now());
        installationRepo.saveAndFlush(install);

        WebhookAuthToken wat = new WebhookAuthToken();
        wat.setWorkspaceId(WORKSPACE);
        wat.setAddonId(ADDON);
        wat.setPath(PATH);
        wat.setEventType(EVENT_TYPE);
        wat.setAuthToken(EncryptedToken.of(codec.encrypt(STORED_AUTH_TOKEN)));
        wat.setCreatedAt(Instant.now());
        webhookRepo.saveAndFlush(wat);
    }

    private String forgeSignature(String authToken, String workspaceId, String addonId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("workspaceId", workspaceId);
        claims.put("addonId", addonId);
        claims.put("authToken", authToken);
        claims.put("backendUrl", "https://api.clockify.me/api");
        return TestJwtForger.forge(claims);
    }

    @Test
    void happyPath_recordsRefreshSignalAndReturns204() throws Exception {
        String sig = forgeSignature(STORED_AUTH_TOKEN, WORKSPACE, ADDON);

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", sig)
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"entry-1\"}"))
                .andExpect(status().isNoContent());

        assertThat(signalRepo.findByWorkspaceIdOrderByReceivedAtDesc(WORKSPACE)).hasSize(1);
    }

    @Test
    void duplicateBodyShortCircuits_noSecondSignal() throws Exception {
        String sig = forgeSignature(STORED_AUTH_TOKEN, WORKSPACE, ADDON);
        String body = "{\"id\":\"entry-7\"}";

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", sig)
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", sig)
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(signalRepo.findByWorkspaceIdOrderByReceivedAtDesc(WORKSPACE)).hasSize(1);
    }

    @Test
    void missingSignature_returns401() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(signalRepo.count()).isZero();
    }

    @Test
    void wrongEventTypeHeader_returns401() throws Exception {
        String sig = forgeSignature(STORED_AUTH_TOKEN, WORKSPACE, ADDON);

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", sig)
                        .header("Clockify-Webhook-Event-Type", "NEW_PROJECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongStoredAuthToken_returns403() throws Exception {
        String sig = forgeSignature("DIFFERENT-AUTH-TOKEN", WORKSPACE, ADDON);

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", sig)
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownWorkspace_returns403() throws Exception {
        String sig = forgeSignature(STORED_AUTH_TOKEN, "ws-other", ADDON);

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", sig)
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void identicalBodyAcrossWorkspaces_bothProcessed_noCrossTenantDedup() throws Exception {
        // Seed a second workspace + webhook auth token so we can deliver
        // the same byte-for-byte body to two tenants. Without workspaceId
        // in the idempotency key the second delivery would be silently
        // deduped because sha256(eventType||body) is identical.
        String ws2 = "ws-wh-two";
        String addon2 = "addon-wh-002";
        String storedTok2 = "webhook-secret-002";

        Installation install2 = new Installation();
        install2.setWorkspaceId(ws2);
        install2.setAddonId(addon2);
        install2.setAuthToken(EncryptedToken.of(codec.encrypt("install-tok-2")));
        install2.setBackendUrl("https://api.clockify.me/api");
        install2.setStatus(InstallationStatus.ACTIVE);
        install2.setInstalledAt(Instant.now());
        install2.setUpdatedAt(Instant.now());
        installationRepo.saveAndFlush(install2);

        WebhookAuthToken wat2 = new WebhookAuthToken();
        wat2.setWorkspaceId(ws2);
        wat2.setAddonId(addon2);
        wat2.setPath(PATH);
        wat2.setEventType(EVENT_TYPE);
        wat2.setAuthToken(EncryptedToken.of(codec.encrypt(storedTok2)));
        wat2.setCreatedAt(Instant.now());
        webhookRepo.saveAndFlush(wat2);

        String body = "{\"id\":\"shared-entry-id\"}";

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", forgeSignature(STORED_AUTH_TOKEN, WORKSPACE, ADDON))
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", forgeSignature(storedTok2, ws2, addon2))
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(signalRepo.findByWorkspaceIdOrderByReceivedAtDesc(WORKSPACE)).hasSize(1);
        assertThat(signalRepo.findByWorkspaceIdOrderByReceivedAtDesc(ws2)).hasSize(1);
    }

    @Test
    void tamperedSignature_returns401() throws Exception {
        String sig = forgeSignature(STORED_AUTH_TOKEN, WORKSPACE, ADDON);
        String tampered = sig.substring(0, sig.length() - 4) + "AAAA";

        mockMvc.perform(post(PATH)
                        .header("Clockify-Signature", tampered)
                        .header("Clockify-Webhook-Event-Type", EVENT_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
