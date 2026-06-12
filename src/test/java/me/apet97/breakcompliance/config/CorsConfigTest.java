package me.apet97.breakcompliance.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the CORS allowlist contract: Clockify-origin preflights must pass
 * with the canonical Access-Control-Allow-Origin response; an attacker-
 * controlled origin must be rejected with 403 (no echoed ACAO). Marketplace
 * reviewers ask for this evidence explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
class CorsConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void clockifyOrigin_preflight_isAllowed() throws Exception {
        mockMvc.perform(options("/api/findings")
                        .header("Origin", "https://app.clockify.me")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Addon-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.clockify.me"));
    }

    @Test
    void regionalClockifySubdomain_preflight_isAllowed() throws Exception {
        // Allowed-origin pattern is `https://*.clockify.me` — covers regional
        // subdomains like euc1.clockify.me without operator intervention.
        mockMvc.perform(options("/api/findings")
                        .header("Origin", "https://euc1.clockify.me")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://euc1.clockify.me"));
    }

    @Test
    void nonClockifyOrigin_preflight_isRejected() throws Exception {
        mockMvc.perform(options("/api/findings")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void localhostOrigin_preflight_isRejected_inDefaultConfig() throws Exception {
        // Localhost is NOT in the default allowlist — operators can extend
        // via CORS_ALLOWED_ORIGIN_PATTERNS for local development, but the
        // shipped default rejects it so production deployments are tight by
        // construction.
        mockMvc.perform(options("/api/findings")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
