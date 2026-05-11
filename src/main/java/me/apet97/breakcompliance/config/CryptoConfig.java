package me.apet97.breakcompliance.config;

import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BreakComplianceCryptoProperties.class)
public class CryptoConfig {

    @Bean
    public TokenCodec tokenCodec(BreakComplianceCryptoProperties props) {
        return new TokenCodec(props.keys(), props.activeKeyId());
    }
}
