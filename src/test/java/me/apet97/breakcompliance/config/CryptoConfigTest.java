package me.apet97.breakcompliance.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Locks the production fallback-key guard. Drives {@link CryptoConfig#validateActiveKey}
 * directly with a synthetic classpath that does NOT contain {@code test-classes},
 * so the guard runs as it would in production. The real Spring bean path is
 * exercised by the existing controller tests (which intentionally bypass the
 * guard via the {@code test-classes} classpath shortcut + surefire env key).
 */
class CryptoConfigTest {

    private static final String PROD_CLASSPATH = "/app/break-compliance.jar:/app/lib/some-dep.jar";
    private static final String STRONG_KEY =
            "deadbeefcafef00ddeadbeefcafef00ddeadbeefcafef00ddeadbeefcafef00d";

    @Test
    void prod_strongKey_passes() {
        var props = new BreakComplianceCryptoProperties("default", Map.of("default", STRONG_KEY));
        var env = new MockEnvironment();

        assertThatCode(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .doesNotThrowAnyException();
    }

    @Test
    void prod_nullKey_throws() {
        var props = new BreakComplianceCryptoProperties("default", null);
        var env = new MockEnvironment();

        assertThatThrownBy(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 hex chars");
    }

    @Test
    void prod_emptyKey_throws() {
        var props = new BreakComplianceCryptoProperties("default", Map.of("default", ""));
        var env = new MockEnvironment();

        assertThatThrownBy(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 hex chars");
    }

    @Test
    void prod_wrongLengthKey_throws() {
        var props = new BreakComplianceCryptoProperties("default", Map.of("default", "abcdef0123"));
        var env = new MockEnvironment();

        assertThatThrownBy(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 hex chars");
    }

    @Test
    void prod_legacyFallbackKey_throws() {
        var props = new BreakComplianceCryptoProperties(
                "default", Map.of("default", CryptoConfig.LEGACY_FALLBACK_KEY));
        var env = new MockEnvironment();

        assertThatThrownBy(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known-weak");
    }

    @Test
    void prod_allZeroKey_throws() {
        var props = new BreakComplianceCryptoProperties(
                "default", Map.of("default", CryptoConfig.ALL_ZERO_KEY));
        var env = new MockEnvironment();

        assertThatThrownBy(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known-weak");
    }

    @Test
    void devProfile_weakKey_bypassesGuard() {
        var props = new BreakComplianceCryptoProperties(
                "default", Map.of("default", CryptoConfig.ALL_ZERO_KEY));
        var env = new MockEnvironment().withProperty("spring.profiles.active", "dev");
        env.setActiveProfiles("dev");

        assertThatCode(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .doesNotThrowAnyException();
    }

    @Test
    void testProfile_weakKey_bypassesGuard() {
        var props = new BreakComplianceCryptoProperties(
                "default", Map.of("default", CryptoConfig.LEGACY_FALLBACK_KEY));
        var env = new MockEnvironment();
        env.setActiveProfiles("test");

        assertThatCode(() -> CryptoConfig.validateActiveKey(props, env, PROD_CLASSPATH))
                .doesNotThrowAnyException();
    }

    @Test
    void testClasspath_weakKey_bypassesGuard() {
        // Mirrors `mvn test`: no "test" profile but the surefire classpath
        // contains "test-classes" so the bypass triggers.
        var props = new BreakComplianceCryptoProperties(
                "default", Map.of("default", CryptoConfig.LEGACY_FALLBACK_KEY));
        var env = new MockEnvironment();

        assertThatCode(() -> CryptoConfig.validateActiveKey(props, env, "/x/test-classes/y"))
                .doesNotThrowAnyException();
    }
}
