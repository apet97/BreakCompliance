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
import me.apet97.breakcompliance.domain.RuleTemplatePresets;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
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

        // Preset and timezone dropdowns emit the user-visible label as the
        // stored value — Clockify's structured-settings DSL only accepts
        // {@code List<String>} for allowedValues, so we sidestep the
        // raw-key complaint by making the keys themselves human-readable.
        // The lifecycle handler maps the inbound label back to the internal
        // slug via {@link RuleTemplatePresets#fromManifestLabel(String)}.
        ClockifySetting appliedPreset = ClockifySetting.builder()
                .id("appliedPresetKey")
                .name("Load preset values")
                .allowAdmins()
                .asDropdownSingle()
                .value(RuleTemplatePresets.CUSTOM_BASIC.manifestLabel())
                .allowedValues(RuleTemplatePresets.ALL.stream()
                        .map(RuleTemplatePresets.Preset::manifestLabel)
                        .toList())
                .description("Loads a jurisdiction starter into the fields below. Picking a preset overwrites every threshold; edits you make afterwards in the same save still win. Defaults match published policy — e.g. California's IWC Wage Orders require a 30-min meal break before the 5th hour.")
                .build();

        ClockifySetting workThreshold = ClockifySetting.builder()
                .id("workThresholdMinutes")
                .name("Work threshold (minutes)")
                .allowAdmins()
                .asNumber()
                .value(240)
                .description("Once a user has logged this many minutes of work in a day, a qualifying break must appear. Example: 240 = 4 hours.")
                .build();

        ClockifySetting breakThreshold = ClockifySetting.builder()
                .id("breakThresholdMinutes")
                .name("Required break (minutes)")
                .allowAdmins()
                .asNumber()
                .value(15)
                .description("Total qualifying break minutes the user must accumulate in a day after crossing the work threshold. Example: 30 (California meal break).")
                .build();

        ClockifySetting minBreakSegment = ClockifySetting.builder()
                .id("minBreakSegmentMinutes")
                .name("Min break segment (minutes)")
                .allowAdmins()
                .asNumber()
                .value(5)
                .description("Break entries shorter than this don't count toward the daily required total. Example: ArbZG §4 requires segments ≥15 min to qualify.")
                .build();

        ClockifySetting maxContinuousWork = ClockifySetting.builder()
                .id("maxContinuousWorkMinutes")
                .name("Max continuous work (minutes)")
                .allowAdmins()
                .asNumber()
                .value(240)
                .description("Longest stretch of uninterrupted work allowed before a qualifying break must occur. Findings emit MAX_CONTINUOUS_WORK_EXCEEDED when a user exceeds this without a break.")
                .build();

        ClockifySetting gracePeriod = ClockifySetting.builder()
                .id("gracePeriodMinutes")
                .name("Grace period (minutes)")
                .allowAdmins()
                .asNumber()
                .value(5)
                .description("Tolerance added to every threshold so trivial overruns don't fire violations. Example: with work threshold 240 and grace 5, a 244-minute shift is still compliant.")
                .build();

        ClockifySetting allowSplit = ClockifySetting.builder()
                .id("allowSplitBreaks")
                .name("Allow split breaks")
                .allowAdmins()
                .asCheckbox()
                .value(Boolean.TRUE)
                .description("ON: the required break total can be summed from multiple qualifying segments. OFF: a single uninterrupted break of the full required length is needed — turn OFF for California's IWC meal-period rule.")
                .build();

        ClockifySetting secondWork = ClockifySetting.builder()
                .id("secondWorkThresholdMinutes")
                .name("Second-tier work threshold (minutes)")
                .allowAdmins()
                .asNumber()
                .value(0)
                .description("Set 0 to disable. Otherwise: when a user crosses this longer shift length, the SECOND-tier required break replaces the first-tier requirement. Example: ArbZG §4 — 540 (9 h) triggers a 45-min total.")
                .build();

        ClockifySetting secondBreak = ClockifySetting.builder()
                .id("secondBreakThresholdMinutes")
                .name("Second-tier required break (minutes)")
                .allowAdmins()
                .asNumber()
                .value(0)
                .description("Set 0 to disable. Otherwise: total qualifying break minutes required once the second-tier work threshold is exceeded. Example: 45 (ArbZG §4 after 9 hours).")
                .build();

        ClockifySetting timezoneStrategy = ClockifySetting.builder()
                .id("timezoneStrategy")
                .name("Time-zone strategy")
                .allowAdmins()
                .asDropdownSingle()
                .value(TimezoneStrategy.ENTRY_TIMEZONE.manifestLabel())
                .allowedValues(java.util.Arrays.stream(TimezoneStrategy.values())
                        .map(TimezoneStrategy::manifestLabel)
                        .toList())
                .description("How to compute the day a time entry belongs to when evaluating break requirements. Entry-local keeps each shift on the day the user worked it (recommended for distributed teams).")
                .build();

        ClockifySetting fallbackDetection = ClockifySetting.builder()
                .id("fallbackDetectionEnabled")
                .name("Detect missing break entries")
                .allowAdmins()
                .asCheckbox()
                .value(Boolean.FALSE)
                .description("ON: flag long uninterrupted work as MISSING_REQUIRED_BREAK even when there is no explicit BREAK-type time entry on that day. Turn ON when your workspace records breaks as gaps between work entries rather than as dedicated BREAK entries.")
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
