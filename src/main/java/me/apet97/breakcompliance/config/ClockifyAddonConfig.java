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
 * Builds the Clockify manifest at startup and exposes it as a Spring bean.
 * Routes are served by Spring {@code @RestController}s; the SDK acts as a
 * manifest builder + JWT verifier, not a routing framework. After the builder
 * produces the manifest we manually populate its lifecycle, webhook and
 * component lists (the SDK's {@code ClockifyAddon.registerXxx} helpers mutate
 * these same lists; we mirror that effect without using the SDK's routing
 * facade so Clockify sees the resources we actually serve).
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
                        ClockifyScope.REPORTS_READ))
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

        // P3.1 — approved / rejected / withdrawn time-off events invalidate
        // the suppression cache. SDK exposes these events natively.
        manifest.getWebhooks().add(ClockifyWebhook.builder()
                .onTimeOffRequestApproved()
                .path("/webhook/time-off-approved")
                .build());
        manifest.getWebhooks().add(ClockifyWebhook.builder()
                .onTimeOffRequestRejected()
                .path("/webhook/time-off-rejected")
                .build());
        manifest.getWebhooks().add(ClockifyWebhook.builder()
                .onTimeOffRequestWithdrawn()
                .path("/webhook/time-off-withdrawn")
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
        // always evaluated. Native structured-settings owns individual threshold
        // fields only. Preset selection deliberately lives in the sidebar iframe,
        // where the app can preview the preset and persist all threshold changes in
        // one backend transaction.

        // Preset and timezone dropdowns emit the user-visible label as the
        // stored value — Clockify's structured-settings DSL only accepts
        // {@code List<String>} for allowedValues, so we sidestep the
        // raw-key complaint by making the keys themselves human-readable.
        // The lifecycle handler maps the inbound label back to the internal
        // slug via {@link RuleTemplatePresets#fromManifestLabel(String)}.
        // The preset chooser lives in the sidebar iframe, not here. Reason:
        // Clockify's native settings UI renders each field independently
        // and never re-fetches sibling fields after a change, so a
        // "pick preset → thresholds populate" interaction can't be made to
        // work without a full page reload. The sidebar handles preset
        // selection with a real preview + confirm flow against
        // POST /api/presets/apply. See sidebar.js renderPresetChooser.
        // We keep tolerating an inbound `appliedPresetKey` in
        // SETTINGS_UPDATED (defensive parser) — legacy workspaces with
        // the field cached might still echo it back.

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
                .placeholder("0 = disabled")
                .description("Set 0 to disable the second tier. Otherwise: when a user crosses this longer shift length, the SECOND-tier required break replaces the first-tier requirement. Example: ArbZG §4 — 540 (9 h) triggers a 45-min total.")
                .build();

        ClockifySetting secondBreak = ClockifySetting.builder()
                .id("secondBreakThresholdMinutes")
                .name("Second-tier required break (minutes)")
                .allowAdmins()
                .asNumber()
                .value(0)
                .placeholder("0 = disabled")
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
                .required(true)
                .description("How to compute the day a time entry belongs to when evaluating break requirements. Entry-local keeps each shift on the day the user worked it (recommended for distributed teams).")
                .build();

        ClockifySetting fallbackDetection = ClockifySetting.builder()
                .id("fallbackDetectionEnabled")
                .name("Detect missing break entries")
                .allowAdmins()
                .asCheckbox()
                .value(Boolean.FALSE)
                .description("ON: detect breaks taken as gaps between work entries. A gap of 5–120 minutes between two consecutive work entries on the same day counts as a qualifying break. Turn ON when your workspace records breaks by stopping the timer rather than logging dedicated BREAK entries.")
                .build();

        // P6.2 — user ids whose schedules shouldn't trigger findings (execs /
        // contractors). Comma- or whitespace-separated. Leave blank to evaluate
        // every user.
        ClockifySetting exemptUsers = ClockifySetting.builder()
                .id("exemptUserIds")
                .name("Exempt user ids")
                .allowAdmins()
                .asTxt()
                // Schema 1.5 rejects empty string defaults; SETTINGS_UPDATED
                // already treats blank strings as null for this optional field.
                .value(" ")
                .placeholder("Comma-separated Clockify user ids")
                .description("Optional. User ids listed here are skipped during evaluation — useful for execs, contractors, or anyone whose schedule isn't subject to the workspace's break policy.")
                .build();

        // P3.3 — workspace override for the refresh-signal debounce window
        // (5–300 s). 0 / blank keeps the application default (20s).
        ClockifySetting refreshDebounce = ClockifySetting.builder()
                .id("refreshDebounceSeconds")
                .name("Refresh debounce (seconds)")
                .allowAdmins()
                .asNumber()
                .value(0)
                .placeholder("0 = use default (20s); accepted range 5–300")
                .description("Optional. How long the addon waits after a Clockify webhook before refreshing findings — bursts of edits coalesce into one re-ingest within this window. Heavy workspaces benefit from longer windows; quiet ones from shorter.")
                .build();

        // P1.3 — scope the detailed-report fetch to APPROVED entries only.
        // OFF by default so the engine still sees work-in-progress entries.
        ClockifySetting excludeUnsubmitted = ClockifySetting.builder()
                .id("excludeUnsubmittedEntries")
                .name("Only evaluate approved entries")
                .allowAdmins()
                .asCheckbox()
                .value(Boolean.FALSE)
                .description("ON: only entries already approved in Clockify count toward break compliance. Work still being edited won't trigger findings. Recommended for workspaces with mandatory approval workflows.")
                .build();

        // P2.9 — severity overrides per finding code. Empty = engine default
        // (VIOLATION). Letting a workspace downgrade to WARNING / INFO is
        // useful when admins want softer signals during a rollout.
        List<String> severityValues = List.of("VIOLATION", "WARNING", "INFO");
        ClockifySetting severityMissing = ClockifySetting.builder()
                .id("severityOverrideMissingBreak")
                .name("Severity — missing break")
                .allowAdmins()
                .asDropdownSingle()
                .value("VIOLATION")
                .allowedValues(severityValues)
                .description("How severe a missing-required-break finding is. Downgrade to WARNING or INFO if you want softer signals during a rollout.")
                .build();
        ClockifySetting severityInsufficient = ClockifySetting.builder()
                .id("severityOverrideInsufficientBreak")
                .name("Severity — insufficient break")
                .allowAdmins()
                .asDropdownSingle()
                .value("VIOLATION")
                .allowedValues(severityValues)
                .description("Severity used when a user took some break but below the required total.")
                .build();
        ClockifySetting severityContinuous = ClockifySetting.builder()
                .id("severityOverrideMaxContinuous")
                .name("Severity — max continuous work")
                .allowAdmins()
                .asDropdownSingle()
                .value("VIOLATION")
                .allowedValues(severityValues)
                .description("Severity used when a user exceeded the max-continuous-work limit without a qualifying break.")
                .build();

        // P1.4 — overnight-shift bucketing. "Start day" matches the
        // historical default; "End day" attributes a shift to the day it
        // ended, useful for night-shift workflows where the bulk of the
        // work was done after midnight.
        ClockifySetting nightShiftAttribution = ClockifySetting.builder()
                .id("nightShiftAttribution")
                .name("Overnight shift bucketing")
                .allowAdmins()
                .asDropdownSingle()
                .value("start-day")
                .allowedValues(List.of("start-day", "end-day"))
                .description("How to attribute time entries whose start and end span a calendar midnight. start-day = whole shift counted on the day it began (default). end-day = whole shift counted on the day it ended.")
                .build();

        ClockifySettingsTab settingsTab = ClockifySettingsTab.builder()
                .id("breakCompliance")
                .name("Break Compliance")
                .settings(List.of(
                        workThreshold,
                        breakThreshold,
                        minBreakSegment,
                        maxContinuousWork,
                        gracePeriod,
                        allowSplit,
                        secondWork,
                        secondBreak,
                        timezoneStrategy,
                        fallbackDetection,
                        exemptUsers,
                        refreshDebounce,
                        excludeUnsubmitted,
                        severityMissing,
                        severityInsufficient,
                        severityContinuous,
                        nightShiftAttribution))
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
