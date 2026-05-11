package me.apet97.breakcompliance.config;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import com.cake.clockify.addonsdk.clockify.model.ClockifyManifest;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyComponent;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyLifecycleEvent;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyScope;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifySetting;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifySettings;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifySettingsTab;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyWebhook;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import me.apet97.breakcompliance.addon.auth.PublicKeyParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
                .iconPath("/icon.svg")
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
        manifest.getWebhooks().add(ClockifyWebhook.builder()
                .onTimeEntryUpdated()
                .path("/webhook/time-entry-updated")
                .build());
        manifest.getWebhooks().add(ClockifyWebhook.builder()
                .onTimeEntryDeleted()
                .path("/webhook/time-entry-deleted")
                .build());

        manifest.getComponents().add(ClockifyComponent.builder()
                .sidebar()
                .allowAdmins()
                .path("/sidebar")
                .label("Break Compliance")
                .build());

        manifest.setSettings(buildStructuredSettings());

        return manifest;
    }

    private static ClockifySettings buildStructuredSettings() {
        // Single-tab settings: the workspace has ONE active rule template,
        // always evaluated. The preset dropdown is a "load values" trigger —
        // picking a preset overwrites the threshold fields with that preset's
        // recommended values on save. Admins then fine-tune any individual
        // field. Each subsequent save without changing the preset just
        // persists the admin's manual edits.

        ClockifySetting appliedPreset = ClockifySetting.builder()
                .id("appliedPresetKey")
                .name("Load preset values")
                .allowAdmins()
                .asDropdownSingle()
                .value("custom-basic")
                .allowedValues(List.of("custom-basic", "california-style", "germany-arbzg-style"))
                .description("Picking a preset here overwrites the threshold fields below with that preset's recommended values. Edit any field after to fine-tune.")
                .build();

        ClockifySetting workThreshold = ClockifySetting.builder()
                .id("workThresholdMinutes")
                .name("Work threshold (minutes)")
                .allowAdmins()
                .asNumber()
                .value(240)
                .description("Minutes of work after which a break is required.")
                .build();

        ClockifySetting breakThreshold = ClockifySetting.builder()
                .id("breakThresholdMinutes")
                .name("Required break (minutes)")
                .allowAdmins()
                .asNumber()
                .value(15)
                .description("Minimum total qualifying break minutes once over the work threshold.")
                .build();

        ClockifySetting minBreakSegment = ClockifySetting.builder()
                .id("minBreakSegmentMinutes")
                .name("Min break segment (minutes)")
                .allowAdmins()
                .asNumber()
                .value(5)
                .description("Shortest break segment that counts toward the required total. Smaller segments are ignored.")
                .build();

        ClockifySetting maxContinuousWork = ClockifySetting.builder()
                .id("maxContinuousWorkMinutes")
                .name("Max continuous work (minutes)")
                .allowAdmins()
                .asNumber()
                .value(240)
                .description("Maximum minutes of uninterrupted work before a qualifying break must be taken.")
                .build();

        ClockifySetting gracePeriod = ClockifySetting.builder()
                .id("gracePeriodMinutes")
                .name("Grace period (minutes)")
                .allowAdmins()
                .asNumber()
                .value(5)
                .description("Tolerance applied to threshold comparisons. Example: 245 min work is still ALLOWED when work threshold = 240, grace = 5.")
                .build();

        ClockifySetting allowSplit = ClockifySetting.builder()
                .id("allowSplitBreaks")
                .name("Allow split breaks")
                .allowAdmins()
                .asCheckbox()
                .value(Boolean.TRUE)
                .description("ON = required break can be summed from multiple qualifying segments. OFF = one uninterrupted break of the required length is needed (California meal-rule style).")
                .build();

        ClockifySetting secondWork = ClockifySetting.builder()
                .id("secondWorkThresholdMinutes")
                .name("Second-tier work threshold (minutes)")
                .allowAdmins()
                .asNumber()
                .value(0)
                .description("Optional second-tier work threshold (e.g. ArbZG 9 h → 45 min). Set 0 to disable the second tier.")
                .build();

        ClockifySetting secondBreak = ClockifySetting.builder()
                .id("secondBreakThresholdMinutes")
                .name("Second-tier required break (minutes)")
                .allowAdmins()
                .asNumber()
                .value(0)
                .description("Required break total once the second-tier work threshold is exceeded. Set 0 to disable.")
                .build();

        ClockifySetting timezoneStrategy = ClockifySetting.builder()
                .id("timezoneStrategy")
                .name("Time-zone strategy")
                .allowAdmins()
                .asDropdownSingle()
                .value("ENTRY_TIMEZONE")
                .allowedValues(List.of("ENTRY_TIMEZONE"))
                .description("How to determine the day boundary for time entries.")
                .build();

        ClockifySetting fallbackDetection = ClockifySetting.builder()
                .id("fallbackDetectionEnabled")
                .name("Detect missing break entries")
                .allowAdmins()
                .asCheckbox()
                .value(Boolean.FALSE)
                .description("ON = flag continuous work blocks over the threshold even when there is no explicit BREAK time entry.")
                .build();

        ClockifySettingsTab settingsTab = ClockifySettingsTab.builder()
                .id("breakCompliance")
                .name("Break Compliance")
                .settings(List.of(
                        appliedPreset,
                        workThreshold,
                        breakThreshold,
                        minBreakSegment,
                        maxContinuousWork,
                        gracePeriod,
                        allowSplit,
                        secondWork,
                        secondBreak,
                        timezoneStrategy,
                        fallbackDetection))
                .build();

        return ClockifySettings.builder()
                .tabs(List.of(settingsTab))
                .build();
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

    /**
     * Shared {@link RestClient} reused by every outbound Clockify call.
     * Previously {@code ClockifyApi} called {@code RestClient.create()} per
     * request, which under burst load (4× retries on each 429 / 5xx) churned
     * thread pools, connection factories, and TCP handshakes. The shared
     * client lets the underlying request factory pool sockets, with explicit
     * connect + read timeouts that match the addon's quick-acknowledge SLA.
     */
    @Bean
    public RestClient clockifyRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }
}
