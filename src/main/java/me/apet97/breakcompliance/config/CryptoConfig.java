package me.apet97.breakcompliance.config;

import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BreakComplianceCryptoProperties.class)
public class CryptoConfig {

    private static final String FALLBACK_KEY = "00000000000000000000000000000000000000000000000000000000000000aa";

    @Bean
    public TokenCodec tokenCodec(BreakComplianceCryptoProperties props, org.springframework.core.env.Environment env) {
        String activeKey = props.keys() != null ? props.keys().get(props.activeKeyId()) : null;
        boolean isDevOrTest = java.util.Arrays.asList(env.getActiveProfiles()).contains("dev")
                || java.util.Arrays.asList(env.getActiveProfiles()).contains("local")
                || java.util.Arrays.asList(env.getActiveProfiles()).contains("test")
                || System.getProperty("java.class.path").contains("test-classes");

        if (!isDevOrTest && FALLBACK_KEY.equals(activeKey)) {
            throw new IllegalStateException("Production MUST set INSTALLATION_TOKEN_KEY and NOT use the fallback key.");
        }
        return new TokenCodec(props.keys(), props.activeKeyId());
    }
}
