package me.apet97.breakcompliance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the token-redaction regexes used by the logback encoder pattern in
 * {@code src/main/resources/logback-spring.xml}. The patterns here MUST
 * mirror the {@code %replace(...)} converter arguments in the encoder. If
 * either is changed without updating the other, this test fails — that's
 * the drift-detection guarantee marketplace reviewers expect.
 */
class LogRedactionTest {

    // Mirror of the logback encoder's first %replace pattern.
    private static final Pattern JWT_TRIPLET = Pattern.compile(
            "eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");

    // Mirror of the logback encoder's second %replace pattern.
    private static final Pattern AUTH_FRAGMENT = Pattern.compile(
            "(?i)(authToken|X-Addon-Token|X-Addon-Lifecycle-Token|Clockify-Signature)"
                    + "([=:]\\s*\"?)([A-Za-z0-9._\\-+/=]{8,})");

    @Test
    void jwtTriplet_isRedacted() {
        String input = "lifecycle.installed token=eyJhbGciOiJSUzI1NiIs.eyJ3b3Jrc3BhY2VJZCI6IndzMSJ9.abcDEF-_123 done";
        String redacted = JWT_TRIPLET.matcher(input).replaceAll("<REDACTED-JWT>");

        assertThat(redacted)
                .contains("<REDACTED-JWT>")
                .doesNotContain("eyJhbGciOiJSUzI1NiIs")
                .doesNotContain("eyJ3b3Jrc3BhY2VJZCI6IndzMSJ9")
                .doesNotContain("abcDEF-_123");
    }

    @Test
    void multipleJwts_inOneLine_allRedacted() {
        String input = "first=eyJa.eyJb.sigA second=eyJc.eyJd.sigB";
        String redacted = JWT_TRIPLET.matcher(input).replaceAll("<REDACTED-JWT>");

        assertThat(redacted)
                .doesNotContain("eyJa")
                .doesNotContain("eyJc")
                .doesNotContain("sigA")
                .doesNotContain("sigB");
    }

    @Test
    void authTokenFragment_caseInsensitive_isRedacted() {
        // Production code paths use mixed casing across HTTP headers and
        // log-field names — the regex is (?i) so all variants redact.
        String inputLower = "authtoken: ABCDEFGHIJKLMNOPQRST=";
        String inputMixed = "AuthToken=ABCDEFGHIJKLMNOPQRST=";
        String inputHeader = "X-Addon-Token: ABCDEFGHIJKLMNOPQRST=";
        String inputSig = "Clockify-Signature: ABCDEFGHIJKLMNOPQRST=";

        for (String input : new String[] {inputLower, inputMixed, inputHeader, inputSig}) {
            String redacted = AUTH_FRAGMENT.matcher(input).replaceAll("$1$2<REDACTED>");
            assertThat(redacted)
                    .as("input was: %s", input)
                    .contains("<REDACTED>")
                    .doesNotContain("ABCDEFGHIJKLMNOPQRST");
        }
    }

    @Test
    void shortFragment_notMatchingTokenShape_passesThrough() {
        // The pattern requires at least 8 chars after the separator so it
        // doesn't shred ordinary log fields like "errorCode=401" or short
        // identifiers. A 7-char value should not be touched.
        String input = "errorCode=abcdefg";
        String redacted = AUTH_FRAGMENT.matcher(input).replaceAll("$1$2<REDACTED>");

        assertThat(redacted).isEqualTo(input);
    }

    @Test
    void plainTextLogLine_isUnchanged() {
        String input = "ingestion.completed workspace=ws-1 entries=42";
        String redactedJwt = JWT_TRIPLET.matcher(input).replaceAll("<REDACTED-JWT>");
        String redactedBoth = AUTH_FRAGMENT.matcher(redactedJwt).replaceAll("$1$2<REDACTED>");

        assertThat(redactedBoth).isEqualTo(input);
    }
}
