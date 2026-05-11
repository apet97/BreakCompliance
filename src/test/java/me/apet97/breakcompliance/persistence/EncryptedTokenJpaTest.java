package me.apet97.breakcompliance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.WebhookAuthTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfig.class)
class EncryptedTokenJpaTest {

    @Autowired
    InstallationRepository installationRepo;

    @Autowired
    WebhookAuthTokenRepository webhookAuthTokenRepo;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final TokenCodec codec = new TokenCodec(
            Map.of("default", "00000000000000000000000000000000000000000000000000000000000000aa"),
            "default");

    @Test
    void installation_authTokenRoundTrip_andStoredAsCiphertext() {
        String plaintext = "secret-clockify-installation-token-xyz";

        Installation i = new Installation();
        i.setWorkspaceId("ws-1");
        i.setAddonId("69e81390556e8f94308aaad8");
        i.setAuthToken(EncryptedToken.of(codec.encrypt(plaintext)));
        i.setBackendUrl("https://api.clockify.me/api");
        i.setReportsUrl("https://reports.api.clockify.me");
        i.setInstallerUserId("user-1");
        i.setStatus(InstallationStatus.ACTIVE);
        Instant now = Instant.now();
        i.setInstalledAt(now);
        i.setUpdatedAt(now);
        installationRepo.saveAndFlush(i);

        byte[] storedCipher = jdbcTemplate.queryForObject(
                "SELECT auth_token_cipher FROM breakcompliance_installations "
                        + "WHERE workspace_id = ? AND addon_id = ?",
                byte[].class,
                "ws-1",
                "69e81390556e8f94308aaad8");
        assertThat(storedCipher).isNotNull();
        assertThat(new String(storedCipher)).doesNotContain(plaintext);

        Installation loaded = installationRepo
                .findById(new Installation.Pk("ws-1", "69e81390556e8f94308aaad8"))
                .orElseThrow();
        assertThat(loaded.getAuthToken().getKeyId()).isEqualTo("default");
        String decrypted = codec.decrypt(loaded.getAuthToken().getKeyId(), loaded.getAuthToken().getCipher());
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void installation_workspaceUniqueAndAddonIdLookup() {
        Installation i = newInstallation("ws-2", "addon-A", "tok-A");
        installationRepo.saveAndFlush(i);

        assertThat(installationRepo.findByWorkspaceId("ws-2")).isPresent();
        assertThat(installationRepo.findByAddonId("addon-A")).isPresent();
    }

    @Test
    void webhookAuthToken_perPathLookupAndRoundTrip() {
        installationRepo.saveAndFlush(newInstallation("ws-3", "addon-B", "inst-tok"));

        WebhookAuthToken w = new WebhookAuthToken();
        w.setWorkspaceId("ws-3");
        w.setAddonId("addon-B");
        w.setPath("/webhook/new-time-entry");
        w.setEventType("NEW_TIME_ENTRY");
        w.setAuthToken(EncryptedToken.of(codec.encrypt("webhook-tok-secret")));
        w.setCreatedAt(Instant.now());
        webhookAuthTokenRepo.saveAndFlush(w);

        WebhookAuthToken loaded = webhookAuthTokenRepo
                .findByWorkspaceIdAndAddonIdAndPath("ws-3", "addon-B", "/webhook/new-time-entry")
                .orElseThrow();
        assertThat(loaded.getEventType()).isEqualTo("NEW_TIME_ENTRY");
        String decrypted = codec.decrypt(loaded.getAuthToken().getKeyId(), loaded.getAuthToken().getCipher());
        assertThat(decrypted).isEqualTo("webhook-tok-secret");
    }

    @Test
    void webhookAuthToken_fkCascadeOnInstallationDelete() {
        installationRepo.saveAndFlush(newInstallation("ws-4", "addon-C", "inst-tok"));

        WebhookAuthToken w = new WebhookAuthToken();
        w.setWorkspaceId("ws-4");
        w.setAddonId("addon-C");
        w.setPath("/webhook/new-time-entry");
        w.setEventType("NEW_TIME_ENTRY");
        w.setAuthToken(EncryptedToken.of(codec.encrypt("wht")));
        w.setCreatedAt(Instant.now());
        webhookAuthTokenRepo.saveAndFlush(w);

        assertThat(webhookAuthTokenRepo.findByWorkspaceIdAndAddonId("ws-4", "addon-C")).hasSize(1);

        installationRepo.deleteById(new Installation.Pk("ws-4", "addon-C"));
        installationRepo.flush();

        assertThat(webhookAuthTokenRepo.findByWorkspaceIdAndAddonId("ws-4", "addon-C")).isEmpty();
    }

    private Installation newInstallation(String workspaceId, String addonId, String tokenPlaintext) {
        Installation i = new Installation();
        i.setWorkspaceId(workspaceId);
        i.setAddonId(addonId);
        i.setAuthToken(EncryptedToken.of(codec.encrypt(tokenPlaintext)));
        i.setBackendUrl("https://api.clockify.me/api");
        i.setStatus(InstallationStatus.ACTIVE);
        Instant now = Instant.now();
        i.setInstalledAt(now);
        i.setUpdatedAt(now);
        return i;
    }
}
