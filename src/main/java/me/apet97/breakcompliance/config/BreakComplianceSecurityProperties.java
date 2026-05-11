package me.apet97.breakcompliance.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "breakcompliance.security")
public record BreakComplianceSecurityProperties(
        List<String> extraFrameAncestors,
        boolean enableHsts,
        List<String> corsAllowedOriginPatterns) {

    private static final List<String> DEFAULT_CORS_ALLOWED_ORIGIN_PATTERNS = List.of("https://*.clockify.me");

    public BreakComplianceSecurityProperties {
        extraFrameAncestors = extraFrameAncestors == null ? List.of() : List.copyOf(extraFrameAncestors);
        corsAllowedOriginPatterns = (corsAllowedOriginPatterns == null || corsAllowedOriginPatterns.isEmpty())
                ? DEFAULT_CORS_ALLOWED_ORIGIN_PATTERNS
                : List.copyOf(corsAllowedOriginPatterns);
    }
}
