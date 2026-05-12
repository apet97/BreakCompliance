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
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
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
    RuleTemplateRepository templatesRepo;

    @Autowired
    TokenCodec codec;

    @BeforeEach
    void cleanState() {
        webhookRepo.deleteAll();
        installationRepo.deleteAll();
        settingsRepo.deleteAll();
        templatesRepo.deleteAll();
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
        // Eager template seeding must also stay idempotent on retried INSTALLED.
        assertThat(templatesRepo.findByWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID)).hasSize(3);
    }

    @Test
    void installed_seedsBuiltInRuleTemplates() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
                .andExpect(status().isOk());

        List<RuleTemplate> seeded = templatesRepo.findByWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        assertThat(seeded).hasSize(3);
        assertThat(seeded).allMatch(t -> t.getType() == RuleTemplateType.BUILT_IN);
        assertThat(seeded).extracting(RuleTemplate::getPresetKey)
                .containsExactlyInAnyOrder("custom-basic", "germany-arbzg-style", "california-style");
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
    void settingsUpdated_emptyArrayLeavesSettingsUntouched() throws Exception {
        installViaLifecycle();
        WorkspaceSettings before = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());

        WorkspaceSettings after = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(after.getDefaultTemplateId()).isEqualTo(before.getDefaultTemplateId());
        assertThat(after.getTimezoneStrategy()).isEqualTo(before.getTimezoneStrategy());
        assertThat(after.isFallbackDetectionEnabled()).isEqualTo(before.isFallbackDetectionEnabled());
    }

    @Test
    void settingsUpdated_appliedPresetKeyChange_overwritesAllThresholds() throws Exception {
        installViaLifecycle();
        // Install seeds the workspace with custom-basic preset values.
        WorkspaceSettings beforeSettings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(beforeSettings.getAppliedPresetKey()).isEqualTo("custom-basic");

        // Admin switches to ArbZG — server should overwrite all 8 thresholds
        // with that preset's values in a single atomic save.
        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"appliedPresetKey\",\"value\":\"germany-arbzg-style\"}]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getAppliedPresetKey()).isEqualTo("germany-arbzg-style");
        // ArbZG §4 thresholds — these are the preset values from
        // RuleTemplatePresets.GERMANY_ARBZG_STYLE, not the manifest defaults.
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(360);
        assertThat(settings.getCustomBreakThresholdMinutes()).isEqualTo(30);
        assertThat(settings.getCustomMinBreakSegmentMinutes()).isEqualTo(15);
        assertThat(settings.getCustomSecondWorkThresholdMinutes()).isEqualTo(540);
        assertThat(settings.getCustomSecondBreakThresholdMinutes()).isEqualTo(45);
    }

    @Test
    void settingsUpdated_appliedPresetManifestLabel_isResolvedToInternalSlug() throws Exception {
        installViaLifecycle();
        // Clockify now emits the user-visible manifest label as the value.
        // The handler must resolve it back to the internal slug before
        // applying the preset's threshold values.
        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"appliedPresetKey\",\"value\":\"Germany (ArbZG §3 + §4)\"}]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getAppliedPresetKey()).isEqualTo("germany-arbzg-style");
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(360);
        assertThat(settings.getCustomSecondWorkThresholdMinutes()).isEqualTo(540);
    }

    @Test
    void settingsUpdated_timezoneStrategyManifestLabel_isResolvedToEnum() throws Exception {
        installViaLifecycle();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"timezoneStrategy\",\"value\":\"Use entry's local time zone\"}]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getTimezoneStrategy()).isEqualTo(TimezoneStrategy.ENTRY_TIMEZONE);
    }

    @Test
    void settingsUpdated_appliedPresetUnchanged_persistsAdminEdit() throws Exception {
        installViaLifecycle();

        // No preset change — admin just tweaks one threshold. The other
        // threshold fields and the appliedPresetKey should be preserved.
        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"workThresholdMinutes\",\"value\":300}]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getAppliedPresetKey()).isEqualTo("custom-basic");
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(300);
        // Other thresholds unchanged — still the custom-basic seed values.
        assertThat(settings.getCustomBreakThresholdMinutes()).isEqualTo(15);
    }

    @Test
    void settingsUpdated_persistsFallbackDetectionEnabled() throws Exception {
        installViaLifecycle();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"fallbackDetectionEnabled\",\"value\":true}]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.isFallbackDetectionEnabled()).isTrue();
    }

    @Test
    void settingsUpdated_persistsTimezoneStrategy() throws Exception {
        installViaLifecycle();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"timezoneStrategy\",\"value\":\"ENTRY_TIMEZONE\"}]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getTimezoneStrategy()).isEqualTo(TimezoneStrategy.ENTRY_TIMEZONE);
    }

    @Test
    void settingsUpdated_persistsAllEightThresholdEdits() throws Exception {
        installViaLifecycle();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" +
                                "{\"id\":\"workThresholdMinutes\",\"value\":180}," +
                                "{\"id\":\"breakThresholdMinutes\",\"value\":20}," +
                                "{\"id\":\"minBreakSegmentMinutes\",\"value\":15}," +
                                "{\"id\":\"maxContinuousWorkMinutes\",\"value\":180}," +
                                "{\"id\":\"gracePeriodMinutes\",\"value\":10}," +
                                "{\"id\":\"allowSplitBreaks\",\"value\":false}," +
                                "{\"id\":\"secondWorkThresholdMinutes\",\"value\":540}," +
                                "{\"id\":\"secondBreakThresholdMinutes\",\"value\":45}" +
                                "]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        // Column names retain the "custom_" prefix from the V2 migration —
        // the manifest field IDs are the un-prefixed forms above. See
        // applySettingUpdate in InstallationService.
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(180);
        assertThat(settings.getCustomBreakThresholdMinutes()).isEqualTo(20);
        assertThat(settings.getCustomMinBreakSegmentMinutes()).isEqualTo(15);
        assertThat(settings.getCustomMaxContinuousWorkMinutes()).isEqualTo(180);
        assertThat(settings.getCustomGracePeriodMinutes()).isEqualTo(10);
        assertThat(settings.getCustomAllowSplitBreaks()).isFalse();
        assertThat(settings.getCustomSecondWorkThresholdMinutes()).isEqualTo(540);
        assertThat(settings.getCustomSecondBreakThresholdMinutes()).isEqualTo(45);
    }

    @Test
    void settingsUpdated_presetChangeAndManualEdit_inSamePayload_editWins() throws Exception {
        installViaLifecycle();

        // Switch to ArbZG AND override workThresholdMinutes in the same
        // SETTINGS_UPDATED delivery. Preset overwrite happens first, then
        // the manual edit lands on top.
        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" +
                                "{\"id\":\"appliedPresetKey\",\"value\":\"germany-arbzg-style\"}," +
                                "{\"id\":\"workThresholdMinutes\",\"value\":420}" +
                                "]"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getAppliedPresetKey()).isEqualTo("germany-arbzg-style");
        // workThreshold = 420 (admin override), NOT 360 (ArbZG preset value).
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(420);
        // Other thresholds = ArbZG values (not overridden).
        assertThat(settings.getCustomBreakThresholdMinutes()).isEqualTo(30);
        assertThat(settings.getCustomSecondWorkThresholdMinutes()).isEqualTo(540);
    }

    @Test
    void settingsUpdated_unknownIdsAreSilentlyIgnored() throws Exception {
        installViaLifecycle();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"unknown-field\",\"value\":\"x\"}]"))
                .andExpect(status().isOk());
        // No exception thrown; row remains valid.
        assertThat(settingsRepo.findById(TestJwtForger.DEFAULT_WORKSPACE_ID)).isPresent();
    }

    // ────────────────────────────────────────────────────────────────────
    // Canonical Clockify SETTINGS_UPDATED payload shape: the live API
    // wraps the array in an object that also carries workspaceId / addonId
    // (see docs/clockify-marketplace/build/manifest/lifecycle.md:96-134).
    // Live Railway log on 2026-05-11 confirmed this is what the dev
    // portal actually delivers. The bare-array tests above stay green to
    // keep backward compatibility.
    // ────────────────────────────────────────────────────────────────────

    @Test
    void settingsUpdated_canonicalObjectShape_appliesPresetChange() throws Exception {
        installViaLifecycle();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "ws-test",
                                  "addonId": "69e81390556e8f94308aaad8",
                                  "settings": [
                                    {"id": "appliedPresetKey", "name": "Load preset values", "value": "germany-arbzg-style"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getAppliedPresetKey()).isEqualTo("germany-arbzg-style");
        // ArbZG threshold overwrites land via the preset-as-loader path,
        // proving the wrapper's `settings` array was actually consumed.
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(360);
        assertThat(settings.getCustomSecondWorkThresholdMinutes()).isEqualTo(540);
    }

    @Test
    void settingsUpdated_canonicalObjectShape_persistsAllEightThresholdEdits() throws Exception {
        installViaLifecycle();

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "ws-test",
                                  "addonId": "69e81390556e8f94308aaad8",
                                  "settings": [
                                    {"id": "workThresholdMinutes",        "value": 180},
                                    {"id": "breakThresholdMinutes",       "value": 20},
                                    {"id": "minBreakSegmentMinutes",      "value": 15},
                                    {"id": "maxContinuousWorkMinutes",    "value": 180},
                                    {"id": "gracePeriodMinutes",          "value": 10},
                                    {"id": "allowSplitBreaks",            "value": false},
                                    {"id": "secondWorkThresholdMinutes",  "value": 540},
                                    {"id": "secondBreakThresholdMinutes", "value": 45}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(180);
        assertThat(settings.getCustomBreakThresholdMinutes()).isEqualTo(20);
        assertThat(settings.getCustomAllowSplitBreaks()).isFalse();
        assertThat(settings.getCustomSecondBreakThresholdMinutes()).isEqualTo(45);
    }

    @Test
    void settingsUpdated_singleObjectShape_isWrappedAsSingletonList() throws Exception {
        installViaLifecycle();

        // Defensive: a hypothetical single-field delivery shape — bare {id,value}
        // object without the wrapper. Parser wraps it in a singleton list.
        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"workThresholdMinutes\",\"value\":275}"))
                .andExpect(status().isOk());

        WorkspaceSettings settings = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(settings.getCustomWorkThresholdMinutes()).isEqualTo(275);
    }

    @Test
    void settingsUpdated_malformedPayload_returns200AndLeavesSettingsUntouched() throws Exception {
        installViaLifecycle();
        WorkspaceSettings before = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();

        // Object with no `settings` field, no `id` field — neither shape we
        // recognise. Should not 400-loop; should ignore and 200.
        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":\"ws-test\",\"unexpected\":\"x\"}"))
                .andExpect(status().isOk());

        WorkspaceSettings after = settingsRepo
                .findById(TestJwtForger.DEFAULT_WORKSPACE_ID).orElseThrow();
        assertThat(after.getCustomWorkThresholdMinutes()).isEqualTo(before.getCustomWorkThresholdMinutes());
        assertThat(after.getAppliedPresetKey()).isEqualTo(before.getAppliedPresetKey());
    }

    @Test
    void settingsUpdated_nullBody_returns200() throws Exception {
        installViaLifecycle();

        // No request body at all (the JWT-only ping case).
        mockMvc.perform(post("/lifecycle/settings-updated")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private void installViaLifecycle() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .header("X-Addon-Lifecycle-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALLED_PAYLOAD))
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
