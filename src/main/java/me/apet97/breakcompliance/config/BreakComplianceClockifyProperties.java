package me.apet97.breakcompliance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional override for Clockify's RSA public-key PEM. Defaults to the
 * canonical PEM embedded in {@link ClockifyAddonConfig}. Override only if
 * Clockify rotates the signing key.
 */
@ConfigurationProperties(prefix = "breakcompliance.clockify")
public record BreakComplianceClockifyProperties(String publicKeyPem) {
}
