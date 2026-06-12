package me.apet97.breakcompliance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import me.apet97.breakcompliance.config.BreakComplianceSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AddonTokenAuthFilterUnitTest {

    @Test
    void apiEndpoint_rejectsDateIatOlderThanCeiling() throws Exception {
        MockHttpServletResponse response = doFilter(Date.from(Instant.now().minus(Duration.ofHours(2))));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void apiEndpoint_rejectsNumericStringIatOlderThanCeiling() throws Exception {
        MockHttpServletResponse response = doFilter(
                Long.toString(Instant.now().minus(Duration.ofHours(2)).getEpochSecond()));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void apiEndpoint_rejectsDateIatInFuture() throws Exception {
        MockHttpServletResponse response = doFilter(Date.from(Instant.now().plus(Duration.ofMinutes(10))));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void apiEndpoint_rejectsNumericStringIatInFuture() throws Exception {
        MockHttpServletResponse response = doFilter(
                Long.toString(Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond()));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private static MockHttpServletResponse doFilter(Object iat) throws Exception {
        ClockifySignatureParser parser = mock(ClockifySignatureParser.class);
        AddonTokenAuthFilter filter = new AddonTokenAuthFilter(
                parser,
                new BreakComplianceSecurityProperties(null, false, null, 1_800L, 60L));
        when(parser.parseClaims("token")).thenReturn(claims(iat));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/session");
        request.addHeader("X-Addon-Token", "token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static Map<String, Object> claims(Object iat) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("workspaceId", "ws-test");
        claims.put("exp", Date.from(Instant.now().plus(Duration.ofHours(1))));
        claims.put("iat", iat);
        return claims;
    }
}
