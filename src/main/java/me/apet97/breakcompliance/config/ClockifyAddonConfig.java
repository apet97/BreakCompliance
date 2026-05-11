package me.apet97.breakcompliance.config;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import com.cake.clockify.addonsdk.clockify.model.ClockifyManifest;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyComponent;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyLifecycleEvent;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyScope;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyWebhook;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import me.apet97.breakcompliance.addon.auth.PublicKeyParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the v1.3 Clockify manifest at startup and exposes it as a Spring
 * bean. Routes are served by Spring {@code @RestController}s; the SDK acts as
 * a manifest builder + JWT verifier, not a routing framework. After the
 * builder produces the manifest we manually populate its lifecycle, webhook
 * and component lists (the SDK's {@code ClockifyAddon.registerXxx} helpers
 * mutate these same lists; we mirror that effect without using the SDK's
 * routing facade so Clockify sees the resources we actually serve).
 */
@Configuration
@EnableConfigurationProperties({BreakComplianceManifestProperties.class, BreakComplianceClockifyProperties.class})
public class ClockifyAddonConfig {

    private static final String DEFAULT_CLOCKIFY_PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAubktufFNO/op+E5WBWL6
            /Y9QRZGSGGCsV00FmPRl5A0mSfQu3yq2Yaq47IlN0zgFy9IUG8/JJfwiehsmbrKa
            49t/xSkpG1u9w1GUyY0g4eKDUwofHKAt3IPw0St4qsWLK9mO+koUo56CGQOEpTui
            5bMfmefVBBfShXTaZOtXPB349FdzSuYlU/5o3L12zVWMutNhiJCKyGfsuu2uXa9+
            6uQnZBw1wO3/QEci7i4TbC+ZXqW1rCcbogSMORqHAP6qSAcTFRmrjFAEsOWiUUhZ
            rLDg2QJ8VTDghFnUhYklNTJlGgfo80qEWe1NLIwvZj0h3bWRfrqZHsD/Yjh0duk6
            yQIDAQAB
            -----END PUBLIC KEY-----
            """;

    @Bean
    @SuppressWarnings("unchecked")
    public ClockifyManifest clockifyManifest(BreakComplianceManifestProperties props) {
        ClockifyManifest manifest = ClockifyManifest.v1_3Builder()
                .key(props.key())
                .name(props.name())
                .baseUrl(props.baseUrl())
                .requireBasicPlan()
                .scopes(List.of(
                        ClockifyScope.TIME_ENTRY_READ,
                        ClockifyScope.USER_READ,
                        ClockifyScope.REPORTS_READ,
                        ClockifyScope.WORKSPACE_READ))
                .description(props.description())
                .build();

        manifest.getLifecycle().add(ClockifyLifecycleEvent.builder()
                .path("/lifecycle/installed")
                .onInstalled()
                .build());
        manifest.getLifecycle().add(ClockifyLifecycleEvent.builder()
                .path("/lifecycle/deleted")
                .onDeleted()
                .build());
        manifest.getLifecycle().add(ClockifyLifecycleEvent.builder()
                .path("/lifecycle/settings-updated")
                .onSettingsUpdated()
                .build());
        manifest.getLifecycle().add(ClockifyLifecycleEvent.builder()
                .path("/lifecycle/status-changed")
                .onStatusChanged()
                .build());

        manifest.getWebhooks().add(ClockifyWebhook.builder()
                .onNewTimeEntry()
                .path("/webhook/new-time-entry")
                .build());

        manifest.getComponents().add(ClockifyComponent.builder()
                .sidebar()
                .allowAdmins()
                .path("/sidebar")
                .label("Break Compliance")
                .build());

        manifest.setSettings("/settings");

        return manifest;
    }

    @Bean
    public RSAPublicKey clockifyPublicKey(BreakComplianceClockifyProperties props) {
        String pem = props.publicKeyPem() != null && !props.publicKeyPem().isBlank()
                ? props.publicKeyPem()
                : DEFAULT_CLOCKIFY_PUBLIC_KEY_PEM;
        return PublicKeyParser.parsePem(pem);
    }

    @Bean
    public ClockifySignatureParser clockifySignatureParser(
            BreakComplianceManifestProperties manifestProps, RSAPublicKey clockifyPublicKey) {
        return new ClockifySignatureParser(manifestProps.key(), clockifyPublicKey);
    }
}
