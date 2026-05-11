package me.apet97.breakcompliance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Locks the user-token authentication contract:
 *
 * <ul>
 *   <li>{@code /sidebar} accepts the token from either the
 *       {@code X-Addon-Token} header (subsequent fetch calls from the iframe
 *       JS after it has scrubbed the URL) or the {@code auth_token} query
 *       parameter (initial top-level iframe navigation, where Clockify
 *       cannot set headers).
 *   <li>{@code /api/*} accepts only the header — query strings are never
 *       trusted for JSON endpoints because tokens would leak into access logs
 *       and Referer headers far more easily.
 *   <li>Missing or invalid tokens always 401.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
class AddonTokenAuthFilterTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void sidebar_acceptsTokenFromAuthTokenQueryParam() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/sidebar").param("auth_token", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void sidebar_acceptsTokenFromXAddonTokenHeader() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/sidebar").header("X-Addon-Token", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void sidebar_rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/sidebar"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sidebar_rejectsBlankQueryToken() throws Exception {
        mockMvc.perform(get("/sidebar").param("auth_token", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sidebar_rejectsTamperedQueryToken() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        mockMvc.perform(get("/sidebar").param("auth_token", tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiEndpoint_rejectsTokenInQueryParam() throws Exception {
        // /api/* never trusts a query-string token — only the header.
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/session").param("auth_token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiEndpoint_acceptsTokenFromHeader() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/session").header("X-Addon-Token", token))
                .andExpect(status().isOk());
    }
}
