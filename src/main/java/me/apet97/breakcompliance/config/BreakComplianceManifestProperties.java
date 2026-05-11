package me.apet97.breakcompliance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "breakcompliance.manifest")
public record BreakComplianceManifestProperties(String key, String name, String baseUrl, String description) {
}
