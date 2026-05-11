package me.apet97.breakcompliance.api;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.ClaimsNormalizer;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies user-iframe tokens delivered via the {@code X-Addon-Token} header
 * on every {@code /api/*} request and {@code /sidebar} / {@code /settings}
 * UI shells. Distinct from {@link me.apet97.breakcompliance.addon.auth.ClockifyLifecycleAuthFilter}
 * which guards the {@code /lifecycle/*} endpoints (those use a different
 * header name and different lifetime claims).
 */
@Component
public class AddonTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AddonTokenAuthFilter.class);
    private static final String HEADER = "X-Addon-Token";

    private final ClockifySignatureParser parser;

    public AddonTokenAuthFilter(ClockifySignatureParser parser) {
        this.parser = parser;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/") || "/sidebar".equals(uri) || "/settings".equals(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Map<String, Object> claims;
        try {
            claims = parser.parseClaims(token);
        } catch (Exception e) {
            log.debug("api.auth.parse-failed path={} reason={}", request.getRequestURI(), e.getClass().getSimpleName());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (claims.get("exp") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        NormalizedClaims normalized;
        try {
            normalized = ClaimsNormalizer.normalize(claims);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (normalized.workspaceId() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        request.setAttribute(RequestAttributes.NORMALIZED_CLAIMS, normalized);
        chain.doFilter(request, response);
    }
}
