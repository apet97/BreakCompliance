package me.apet97.breakcompliance.addon.auth;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the {@code X-Addon-Lifecycle-Token} JWT on every {@code POST /lifecycle/*}
 * request. On success, normalises claims and sets them on the request as the
 * {@link RequestAttributes#NORMALIZED_CLAIMS} attribute so the lifecycle
 * controller can read workspaceId / addonId / backendUrl from a single
 * already-verified source.
 *
 * <p>The SDK's {@link ClockifySignatureParser} enforces RS256 signature,
 * {@code iss=clockify}, {@code type=addon}, {@code sub=<manifest-key>}, and
 * JJWT's parser rejects an expired {@code exp}. We additionally require
 * {@code exp} to be present (no token without an expiry) and reject if
 * either {@code workspaceId} or {@code addonId} is missing after
 * alias normalization.
 *
 * <p>Failures return {@code 401} with no body. The token must never appear
 * in error responses or logs.
 */
@Component
public class ClockifyLifecycleAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClockifyLifecycleAuthFilter.class);
    private static final String HEADER = "X-Addon-Lifecycle-Token";

    private final ClockifySignatureParser parser;

    public ClockifyLifecycleAuthFilter(ClockifySignatureParser parser) {
        this.parser = parser;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/lifecycle/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            log.debug("lifecycle.auth.missing-token path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Map<String, Object> claims;
        try {
            claims = parser.parseClaims(token);
        } catch (Exception e) {
            log.debug("lifecycle.auth.parse-failed path={} reason={}", request.getRequestURI(), e.getClass().getSimpleName());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (claims.get("exp") == null) {
            log.debug("lifecycle.auth.missing-exp path={}", request.getRequestURI());
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
        if (normalized.workspaceId() == null || normalized.addonId() == null) {
            log.debug("lifecycle.auth.missing-identity path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        request.setAttribute(RequestAttributes.NORMALIZED_CLAIMS, normalized);
        chain.doFilter(request, response);
    }
}
