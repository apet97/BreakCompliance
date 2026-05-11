package me.apet97.breakcompliance.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "breakcompliance.security")
public record BreakComplianceSecurityProperties(
        List<String> extraFrameAncestors,
        boolean enableHsts,
        List<String> corsAllowedOriginPatterns,
        long sidebarTokenMaxIatAgeSeconds,
        long iatClockSkewSeconds) {

    private static final List<String> DEFAULT_CORS_ALLOWED_ORIGIN_PATTERNS = List.of("https://*.clockify.me");
    private static final long DEFAULT_SIDEBAR_TOKEN_MAX_IAT_AGE_SECONDS = 30L * 60L;
    private static final long DEFAULT_IAT_CLOCK_SKEW_SECONDS = 60L;

    public BreakComplianceSecurityProperties {
        extraFrameAncestors = extraFrameAncestors == null ? List.of() : List.copyOf(extraFrameAncestors);
        corsAllowedOriginPatterns = (corsAllowedOriginPatterns == null || corsAllowedOriginPatterns.isEmpty())
                ? DEFAULT_CORS_ALLOWED_ORIGIN_PATTERNS
                : List.copyOf(corsAllowedOriginPatterns);
        if (sidebarTokenMaxIatAgeSeconds <= 0L) {
            sidebarTokenMaxIatAgeSeconds = DEFAULT_SIDEBAR_TOKEN_MAX_IAT_AGE_SECONDS;
        }
        if (iatClockSkewSeconds < 0L) {
            iatClockSkewSeconds = DEFAULT_IAT_CLOCK_SKEW_SECONDS;
        }
    }
}
