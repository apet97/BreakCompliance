package me.apet97.breakcompliance.addon.auth;

import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitutes the Clockify RSA public key with the test forger's public key
 * so the SDK's signature parser verifies tokens signed by
 * {@link TestJwtForger#PRIVATE_KEY}. The {@code @Bean} method is named
 * differently from the production bean to avoid Spring's
 * {@code BeanDefinitionOverrideException} (default-strict in 3.x) — the
 * {@code @Primary} marker resolves the type-injection ambiguity in favour of
 * the test key.
 */
@TestConfiguration
public class TestClockifyKeyConfig {

    @Bean
    @Primary
    public RSAPublicKey testClockifyPublicKey() {
        return TestJwtForger.PUBLIC_KEY;
    }
}
