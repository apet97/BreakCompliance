package me.apet97.breakcompliance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
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
}
