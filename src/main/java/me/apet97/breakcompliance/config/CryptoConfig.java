package me.apet97.breakcompliance.config;

import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(BreakComplianceCryptoProperties.class)
public class CryptoConfig {

    /** Historical surefire-env fallback key (32 bytes, ends in ...aa). */
    static final String LEGACY_FALLBACK_KEY =
            "00000000000000000000000000000000000000000000000000000000000000aa";
    /** application-dev.yaml's all-zero key — also unsafe for production. */
    static final String ALL_ZERO_KEY = "0".repeat(64);
    /** Required AES-256 hex length. */
    static final int REQUIRED_HEX_LENGTH = 64;

    @Bean
    public TokenCodec tokenCodec(BreakComplianceCryptoProperties props, Environment env) {
        validateActiveKey(props, env, System.getProperty("java.class.path"));
        return new TokenCodec(props.keys(), props.activeKeyId());
    }

    /**
     * Reject known-weak keys in non-dev/test profiles. Extracted so tests can
     * drive the production path with a classpath that does not contain
     * {@code test-classes} — under {@code mvn test} the bypass would otherwise
     * always fire and silently skip the guard.
     */
    static void validateActiveKey(
            BreakComplianceCryptoProperties props, Environment env, String classpath) {
        String activeKey = props.keys() != null ? props.keys().get(props.activeKeyId()) : null;
        boolean isDevOrTest = java.util.Arrays.asList(env.getActiveProfiles()).contains("dev")
                || java.util.Arrays.asList(env.getActiveProfiles()).contains("local")
                || java.util.Arrays.asList(env.getActiveProfiles()).contains("test")
                || (classpath != null && classpath.contains("test-classes"));
        if (isDevOrTest) {
            return;
        }
        // Production: must be a strong, operator-supplied AES-256 hex key.
        // Reject every known-weak shape, not just the legacy fallback —
        // a misconfigured deploy that picks up application-dev.yaml's
        // all-zero key would silently encrypt every installation token with
        // a public constant otherwise.
        if (activeKey == null || activeKey.isBlank()) {
            throw new IllegalStateException(
                    "Production MUST set INSTALLATION_TOKEN_KEY (64 hex chars / AES-256).");
        }
        if (activeKey.length() != REQUIRED_HEX_LENGTH) {
            throw new IllegalStateException(
                    "Production INSTALLATION_TOKEN_KEY must be exactly "
                            + REQUIRED_HEX_LENGTH + " hex chars; got length=" + activeKey.length() + ".");
        }
        if (activeKey.equalsIgnoreCase(LEGACY_FALLBACK_KEY)
                || activeKey.equalsIgnoreCase(ALL_ZERO_KEY)) {
            throw new IllegalStateException(
                    "Production MUST set INSTALLATION_TOKEN_KEY to a strong, secret value — refusing a known-weak fallback/dev key.");
        }
    }
}
