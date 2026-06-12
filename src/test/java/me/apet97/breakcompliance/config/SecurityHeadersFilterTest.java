package me.apet97.breakcompliance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
class SecurityHeadersFilterTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void cspIncludesClockifyAppOrigin() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull().contains("frame-ancestors https://app.clockify.me");
    }

    @Test
    void responseHasXContentTypeOptionsNosniff() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void responseHasReferrerPolicyNoReferrer() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void responseHasPermissionsPolicyLockedDown() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        String pp = result.getResponse().getHeader("Permissions-Policy");
        assertThat(pp).contains("camera=()", "microphone=()", "geolocation=()");
    }

    @Test
    void hstsDisabledByDefault() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        assertThat(result.getResponse().getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void cspAllowsClockifyWildcardFrameAncestor() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("https://*.clockify.me");
    }

    @Test
    void cspAllowsClockifyUiCdnInStyleAndImgAndFontSrc() throws Exception {
        // The sidebar loads Clockify's native UI stylesheet from
        // resources.developer.clockify.me. The CDN serves fonts and images
        // too, so style-src, img-src, and font-src all whitelist it.
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("style-src 'self' 'unsafe-inline' https://resources.developer.clockify.me");
        assertThat(csp).contains("img-src 'self' data: https://resources.developer.clockify.me");
        assertThat(csp).contains("font-src 'self' https://resources.developer.clockify.me");
    }

    @Test
    void sidebarDoesNotRenderInlineScriptsBlockedBySelfOnlyCsp() throws Exception {
        MvcResult result = mockMvc.perform(get("/sidebar")
                        .param("auth_token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'self'");
        assertThat(body).doesNotContain("<script>");
        assertThat(body).contains("<script src=\"/theme-init.js\"></script>");
        assertThat(body).contains("<script type=\"module\" src=\"/sidebar.js\"></script>");
    }

    @Test
    void corsPreflightFromDeveloperPortalIsAllowed() throws Exception {
        MvcResult result = mockMvc.perform(options("/manifest")
                        .header(HttpHeaders.ORIGIN, "https://developer.clockify.me")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("https://developer.clockify.me");
        String allowedMethods = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
        assertThat(allowedMethods).contains("GET", "POST");
    }

    @Test
    void corsPreflightFromAppOriginIsAllowed() throws Exception {
        MvcResult result = mockMvc.perform(options("/manifest")
                        .header(HttpHeaders.ORIGIN, "https://app.clockify.me")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("https://app.clockify.me");
    }

    @Test
    void rootReturnsOk() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }
}
