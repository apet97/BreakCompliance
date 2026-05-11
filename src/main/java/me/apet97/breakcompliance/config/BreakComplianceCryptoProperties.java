package me.apet97.breakcompliance.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "breakcompliance.crypto")
public record BreakComplianceCryptoProperties(String activeKeyId, Map<String, String> keys) {
}
