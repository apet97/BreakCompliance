package me.apet97.breakcompliance.addon.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
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

    private final InstallationRepository installationRepo;
    private final WebhookAuthTokenRepository webhookRepo;
    private final WorkspaceSettingsRepository settingsRepo;
    private final TokenCodec codec;

    public InstallationService(
            InstallationRepository installationRepo,
            WebhookAuthTokenRepository webhookRepo,
            WorkspaceSettingsRepository settingsRepo,
            TokenCodec codec) {
        this.installationRepo = installationRepo;
        this.webhookRepo = webhookRepo;
        this.settingsRepo = settingsRepo;
        this.codec = codec;
    }

    @Transactional
    public void handleInstalled(NormalizedClaims claims, Map<String, Object> payload) {
        String workspaceId = claims.workspaceId();
        String addonId = claims.addonId();
        Instant now = Instant.now();

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
            settings.setCreatedAt(now);
            settings.setUpdatedAt(now);
            settingsRepo.save(settings);
        }
        log.info("lifecycle.installed workspace={} addon={}", workspaceId, addonId);
    }

    @Transactional
    public void handleDeleted(NormalizedClaims claims) {
        Installation.Pk pk = new Installation.Pk(claims.workspaceId(), claims.addonId());
        if (installationRepo.existsById(pk)) {
            installationRepo.deleteById(pk);
            log.info("lifecycle.deleted workspace={} addon={}", claims.workspaceId(), claims.addonId());
        }
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
