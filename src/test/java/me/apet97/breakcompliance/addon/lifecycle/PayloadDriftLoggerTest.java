package me.apet97.breakcompliance.addon.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayloadDriftLoggerTest {

    private static final Set<String> KNOWN = Set.of("addonId", "workspaceId", "authToken");

    private PayloadDriftLogger logger;

    @BeforeEach
    void freshLogger() {
        logger = new PayloadDriftLogger();
    }

    @Test
    void allKnownKeys_recordsNoFingerprint() {
        logger.check("ctx", Map.of("addonId", "a", "workspaceId", "w", "authToken", "t"), KNOWN);
        assertThat(logger.seenFingerprintCount()).isZero();
    }

    @Test
    void firstUnknownKey_recordsFingerprint() {
        logger.check("ctx", Map.of("addonId", "a", "newField", "x"), KNOWN);
        assertThat(logger.seenFingerprintCount()).isEqualTo(1);
    }

    @Test
    void sameUnknownKey_isSilentSecondTime() {
        logger.check("ctx", Map.of("newField", "x"), KNOWN);
        int afterFirst = logger.seenFingerprintCount();

        // Different value, same unknown key → same fingerprint → no new entry.
        logger.check("ctx", Map.of("newField", "different value"), KNOWN);

        assertThat(logger.seenFingerprintCount()).isEqualTo(afterFirst);
    }

    @Test
    void differentUnknownKey_recordsSeparateFingerprint() {
        logger.check("ctx", Map.of("newField", "x"), KNOWN);
        logger.check("ctx", Map.of("otherField", "y"), KNOWN);
        assertThat(logger.seenFingerprintCount()).isEqualTo(2);
    }

    @Test
    void sameUnknownKeys_inDifferentContext_recordsSeparateFingerprint() {
        logger.check("ctx-a", Map.of("newField", "x"), KNOWN);
        logger.check("ctx-b", Map.of("newField", "x"), KNOWN);
        assertThat(logger.seenFingerprintCount()).isEqualTo(2);
    }

    @Test
    void unknownKeyOrderDoesNotAffectFingerprint() {
        logger.check("ctx", Map.of("foo", 1, "bar", 2), KNOWN);
        int afterFirst = logger.seenFingerprintCount();

        // Different insertion order, same set of unknowns → same fingerprint.
        logger.check("ctx", Map.of("bar", 99, "foo", 88), KNOWN);

        assertThat(logger.seenFingerprintCount()).isEqualTo(afterFirst);
    }

    @Test
    void nullPayload_isNoOp() {
        logger.check("ctx", null, KNOWN);
        assertThat(logger.seenFingerprintCount()).isZero();
    }
}
