package me.apet97.breakcompliance.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "breakcompliance.security")
public record BreakComplianceSecurityProperties(
        List<String> extraFrameAncestors,
        boolean enableHsts) {

    public BreakComplianceSecurityProperties {
        extraFrameAncestors = extraFrameAncestors == null ? List.of() : List.copyOf(extraFrameAncestors);
    }
}
