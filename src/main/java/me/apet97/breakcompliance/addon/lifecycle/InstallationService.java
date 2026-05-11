package me.apet97.breakcompliance.addon.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.domain.RuleTemplatePresets;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.WebhookAuthTokenRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import me.apet97.breakcompliance.util.WebhookPathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists Clockify lifecycle effects. Idempotent on every operation so a
 * retried {@code INSTALLED} delivery doesn't duplicate webhook tokens or
 * reset {@code installed_at}, and a stale {@code DELETED} after a fresh
 * {@code INSTALLED} doesn't remove the new installation (FK cascade on
 * webhook_auth_tokens handles secondary cleanup).
 */
@Service
public class InstallationService {

    private static final Logger log = LoggerFactory.getLogger(InstallationService.class);

    private static final java.util.Set<String> KNOWN_INSTALLED_KEYS = java.util.Set.of(
            "addonId", "workspaceId", "authToken", "apiUrl", "backendUrl",
            "asUser", "addonUserId", "webhooks", "baseUrl", "reportsUrl",
            "locationsUrl", "screenshotsUrl", "scopes", "status", "user");

    private final InstallationRepository installationRepo;
    private final WebhookAuthTokenRepository webhookRepo;
    private final WorkspaceSettingsRepository settingsRepo;
    private final RuleTemplateRepository templatesRepo;
    private final PayloadDriftLogger driftLogger;
    private final TokenCodec codec;

    public InstallationService(
            InstallationRepository installationRepo,
            WebhookAuthTokenRepository webhookRepo,
            WorkspaceSettingsRepository settingsRepo,
            RuleTemplateRepository templatesRepo,
            PayloadDriftLogger driftLogger,
            TokenCodec codec) {
        this.installationRepo = installationRepo;
        this.webhookRepo = webhookRepo;
        this.settingsRepo = settingsRepo;
        this.templatesRepo = templatesRepo;
        this.driftLogger = driftLogger;
        this.codec = codec;
    }

    @Transactional
    public void handleInstalled(NormalizedClaims claims, Map<String, Object> payload) {
        // Drift-check the inbound payload so a Clockify schema bump emits a
        // WARN the first time we see a new top-level key (handler still runs).
        driftLogger.check("lifecycle.installed", payload, KNOWN_INSTALLED_KEYS);

        String workspaceId = claims.workspaceId();
        String addonId = claims.addonId();
        Instant now = Instant.now();

        if (claims.backendUrl() == null || claims.backendUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "INSTALLED missing backendUrl after enrichment; payload.apiUrl was: "
                            + payload.get("apiUrl"));
        }

        String authTokenPlaintext = stringValue(payload.get("authToken"));
        if (authTokenPlaintext == null) {
            throw new IllegalArgumentException("INSTALLED payload missing authToken");
        }
        EncryptedToken authToken = EncryptedToken.of(codec.encrypt(authTokenPlaintext));

        Installation install = installationRepo
                .findById(new Installation.Pk(workspaceId, addonId))
                .orElseGet(Installation::new);
        if (install.getInstalledAt() == null) {
            install.setInstalledAt(now);
        }
        install.setWorkspaceId(workspaceId);
        install.setAddonId(addonId);
        install.setAuthToken(authToken);
        install.setBackendUrl(claims.backendUrl());
        install.setReportsUrl(claims.reportsUrl());
        install.setInstallerUserId(claims.userId());
        install.setStatus(InstallationStatus.ACTIVE);
        install.setUpdatedAt(now);
        installationRepo.save(install);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> webhooks = (List<Map<String, Object>>) payload.getOrDefault("webhooks", List.of());
        for (Map<String, Object> wh : webhooks) {
            String path = WebhookPathNormalizer.normalize(stringValue(wh.get("path")));
            String eventType = stringValue(wh.get("event"));
            String whTokenPlaintext = stringValue(wh.get("authToken"));
            if (path == null || whTokenPlaintext == null) {
                continue;
            }
            WebhookAuthToken wat = webhookRepo
                    .findByWorkspaceIdAndAddonIdAndPath(workspaceId, addonId, path)
                    .orElseGet(WebhookAuthToken::new);
            if (wat.getCreatedAt() == null) {
                wat.setCreatedAt(now);
            }
            wat.setWorkspaceId(workspaceId);
            wat.setAddonId(addonId);
            wat.setPath(path);
            wat.setEventType(eventType == null ? "" : eventType);
            wat.setAuthToken(EncryptedToken.of(codec.encrypt(whTokenPlaintext)));
            webhookRepo.save(wat);
        }

        if (!settingsRepo.existsById(workspaceId)) {
            WorkspaceSettings settings = new WorkspaceSettings();
            settings.setWorkspaceId(workspaceId);
            settings.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
            settings.setFallbackDetectionEnabled(false);
            settings.setAppliedPresetKey("custom-basic");
            // Seed the threshold columns from the default preset so a fresh
            // workspace lands on usable values, not all-null. Admin can pick
            // a different preset in the structured-settings page to overwrite.
            applyPresetToSettings(settings, "custom-basic");
            settings.setCreatedAt(now);
            settings.setUpdatedAt(now);
            settingsRepo.save(settings);
        }
        seedDefaultTemplates(workspaceId, now);
        log.info("lifecycle.installed workspace={} addon={}", workspaceId, addonId);
    }

    /**
     * Overwrite the threshold columns on {@code settings} with the values
     * from the named preset (one of {@code RuleTemplatePresets.ALL}). No-op
     * when the preset key is unknown — caller must validate upstream.
     */
    private static void applyPresetToSettings(WorkspaceSettings settings, String presetKey) {
        var preset = RuleTemplatePresets.ALL.stream()
                .filter(p -> p.key().equals(presetKey))
                .findFirst()
                .orElse(null);
        if (preset == null) return;
        settings.setCustomWorkThresholdMinutes(preset.workThresholdMinutes());
        settings.setCustomBreakThresholdMinutes(preset.requiredBreakMinutes());
        settings.setCustomMinBreakSegmentMinutes(preset.minimumValidBreakSegmentMinutes());
        settings.setCustomMaxContinuousWorkMinutes(preset.maxContinuousWorkMinutesBeforeBreak());
        settings.setCustomGracePeriodMinutes(preset.gracePeriodMinutes());
        settings.setCustomAllowSplitBreaks(preset.allowSplitBreaks());
        settings.setCustomSecondWorkThresholdMinutes(
                preset.secondThresholdMinutes() != null ? preset.secondThresholdMinutes() : 0);
        settings.setCustomSecondBreakThresholdMinutes(
                preset.secondRequiredBreakMinutes() != null ? preset.secondRequiredBreakMinutes() : 0);
    }

    /**
     * Idempotently materialize the built-in rule templates for a workspace
     * on every INSTALLED delivery. The {@code TemplatesController} also seeds
     * lazily on first {@code GET /api/templates}, but seeding eagerly here
     * makes the templates available to the native Clockify structured-settings
     * page (which Clockify renders server-side off the manifest, before the
     * sidebar JS ever runs).
     */
    private void seedDefaultTemplates(String workspaceId, Instant now) {
        for (var preset : RuleTemplatePresets.ALL) {
            RuleTemplate entity = preset.toEntity(workspaceId, now);
            if (!templatesRepo.existsById(new RuleTemplate.Pk(workspaceId, entity.getId()))) {
                templatesRepo.save(entity);
            }
        }
    }

    @Transactional
    public void handleDeleted(NormalizedClaims claims) {
        Installation.Pk pk = new Installation.Pk(claims.workspaceId(), claims.addonId());
        if (installationRepo.existsById(pk)) {
            installationRepo.deleteById(pk);
            log.info("lifecycle.deleted workspace={} addon={}", claims.workspaceId(), claims.addonId());
        }
    }

    /**
     * Persist a SETTINGS_UPDATED payload. Clockify delivers an array of
     * {@code {id, value, ...}} entries — one per setting changed in the
     * native structured-settings page. Unknown ids are ignored; malformed
     * values for a known id are silently dropped (Clockify's own validation
     * upstream is the source of truth for accepted values, and we don't want
     * a single bad field to nack the whole delivery and trigger redelivery
     * loops).
     */
    @Transactional
    public void handleSettingsUpdated(NormalizedClaims claims, List<Map<String, Object>> updates) {
        String workspaceId = claims.workspaceId();
        Instant now = Instant.now();
        WorkspaceSettings settings = settingsRepo.findById(workspaceId).orElseGet(() -> {
            WorkspaceSettings fresh = new WorkspaceSettings();
            fresh.setWorkspaceId(workspaceId);
            fresh.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
            fresh.setFallbackDetectionEnabled(false);
            fresh.setAppliedPresetKey("custom-basic");
            applyPresetToSettings(fresh, "custom-basic");
            fresh.setCreatedAt(now);
            return fresh;
        });

        // Phase 1: scan for a preset change. If the incoming appliedPresetKey
        // differs from the stored value, overwrite all 8 threshold columns
        // with the preset's values BEFORE applying any per-field edits in
        // phase 2 (so a single save can "load preset values + tweak one
        // field" atomically).
        String incomingPresetKey = extractPresetKey(updates);
        boolean presetChanged = false;
        if (incomingPresetKey != null
                && !incomingPresetKey.equals(settings.getAppliedPresetKey())
                && isKnownPreset(incomingPresetKey)) {
            applyPresetToSettings(settings, incomingPresetKey);
            settings.setAppliedPresetKey(incomingPresetKey);
            presetChanged = true;
            log.info("lifecycle.settings-updated.preset-changed workspace={} preset={}",
                    workspaceId, incomingPresetKey);
        }

        // Phase 2: apply each per-field update on top of (post-overwrite)
        // settings. Admin can change preset + tweak a single threshold in
        // the same SETTINGS_UPDATED delivery and the tweak wins.
        boolean fieldChanged = false;
        for (Map<String, Object> entry : updates) {
            if (entry == null) continue;
            Object idObj = entry.get("id");
            if (!(idObj instanceof String id) || id.isBlank()) continue;
            Object value = entry.get("value");
            fieldChanged |= applySettingUpdate(settings, id, value);
        }

        if (presetChanged || fieldChanged) {
            settings.setUpdatedAt(now);
            settingsRepo.save(settings);
            log.info("lifecycle.settings-updated workspace={}", workspaceId);
        }
    }

    private static String extractPresetKey(List<Map<String, Object>> updates) {
        for (Map<String, Object> entry : updates) {
            if (entry == null) continue;
            if ("appliedPresetKey".equals(entry.get("id"))
                    && entry.get("value") instanceof String s
                    && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static boolean isKnownPreset(String key) {
        return RuleTemplatePresets.ALL.stream().anyMatch(p -> p.key().equals(key));
    }

    private boolean applySettingUpdate(WorkspaceSettings settings, String id, Object value) {
        return switch (id) {
            case "appliedPresetKey" -> {
                // Handled by extractPresetKey + applyPresetToSettings in phase 1
                // so the threshold columns get the preset values BEFORE per-field
                // edits land. Repeat-application here is a no-op.
                yield false;
            }
            case "timezoneStrategy" -> {
                if (value instanceof String s && !s.isBlank()) {
                    try {
                        settings.setTimezoneStrategy(TimezoneStrategy.valueOf(s));
                        yield true;
                    } catch (IllegalArgumentException ignored) {
                        yield false;
                    }
                }
                yield false;
            }
            case "fallbackDetectionEnabled" -> {
                if (value instanceof Boolean b) {
                    settings.setFallbackDetectionEnabled(b);
                    yield true;
                }
                yield false;
            }
            // New manifest field IDs map to the existing custom_* columns so
            // we don't need a destructive column rename. The "custom_" prefix
            // is now historical; semantically these are the workspace's
            // active rule template thresholds.
            case "workThresholdMinutes" -> setNullableMinutes(value, settings::setCustomWorkThresholdMinutes);
            case "breakThresholdMinutes" -> setNullableMinutes(value, settings::setCustomBreakThresholdMinutes);
            case "minBreakSegmentMinutes" -> setNullableMinutes(value, settings::setCustomMinBreakSegmentMinutes);
            case "maxContinuousWorkMinutes" -> setNullableMinutes(value, settings::setCustomMaxContinuousWorkMinutes);
            case "gracePeriodMinutes" -> setNullableMinutes(value, settings::setCustomGracePeriodMinutes);
            case "allowSplitBreaks" -> {
                if (value instanceof Boolean b) {
                    settings.setCustomAllowSplitBreaks(b);
                    yield true;
                }
                yield false;
            }
            case "secondWorkThresholdMinutes" -> setNullableMinutes(value, settings::setCustomSecondWorkThresholdMinutes);
            case "secondBreakThresholdMinutes" -> setNullableMinutes(value, settings::setCustomSecondBreakThresholdMinutes);
            default -> false;
        };
    }

    private static boolean setNullableMinutes(Object value, java.util.function.Consumer<Integer> setter) {
        if (value == null) {
            setter.accept(null);
            return true;
        }
        Integer parsed = null;
        if (value instanceof Number n) parsed = n.intValue();
        else if (value instanceof String s && !s.isBlank()) {
            try { parsed = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return false; }
        }
        if (parsed == null || parsed < 0) return false;
        setter.accept(parsed);
        return true;
    }

    @Transactional
    public void handleStatusChanged(NormalizedClaims claims, String status) {
        installationRepo.findById(new Installation.Pk(claims.workspaceId(), claims.addonId())).ifPresent(install -> {
            InstallationStatus next;
            try {
                next = InstallationStatus.valueOf(status);
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("lifecycle.status-changed.unknown-status workspace={} status={}", claims.workspaceId(), status);
                return;
            }
            install.setStatus(next);
            install.setUpdatedAt(Instant.now());
            installationRepo.save(install);
            log.info("lifecycle.status-changed workspace={} status={}", claims.workspaceId(), next);
        });
    }

    private static String stringValue(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }
}
