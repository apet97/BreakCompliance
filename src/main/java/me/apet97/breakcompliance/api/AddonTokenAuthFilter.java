package me.apet97.breakcompliance.api;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.ClaimsNormalizer;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.config.BreakComplianceSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies user-iframe tokens delivered via the {@code X-Addon-Token} header
 * on every {@code /api/*} request and the {@code /sidebar} UI shell. For the
 * iframe HTML load Clockify can only pass the token as the {@code auth_token}
 * URL query parameter (a top-level navigation can't set request headers), so
 * on the {@code /sidebar} route we accept the query-string form too; the
 * sidebar JS then scrubs it via {@code history.replaceState} and forwards it
 * as {@code X-Addon-Token} on every subsequent {@code /api/*} call.
 *
 * <p>Distinct from {@link me.apet97.breakcompliance.addon.auth.ClockifyLifecycleAuthFilter}
 * which guards the {@code /lifecycle/*} endpoints (different header name and
 * different lifetime claims). Settings UI is delivered by Clockify natively
 * via structured-settings declarations in the manifest, so the add-on does
 * not serve its own {@code /settings} page.
 */
@Component
public class AddonTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AddonTokenAuthFilter.class);
    private static final String HEADER = "X-Addon-Token";
    private static final String QUERY_PARAM = "auth_token";

    private final ClockifySignatureParser parser;
    private final BreakComplianceSecurityProperties securityProps;

    public AddonTokenAuthFilter(ClockifySignatureParser parser, BreakComplianceSecurityProperties securityProps) {
        this.parser = parser;
        this.securityProps = securityProps;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/") || "/sidebar".equals(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if ((token == null || token.isBlank()) && "/sidebar".equals(request.getRequestURI())) {
            token = request.getParameter(QUERY_PARAM);
        }
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
        // Replay-protection: reject tokens whose iat claim is older than the
        // configured ceiling (default 30 min — matches the documented
        // ~30 min token rotation window). Operators can tighten this via
        // breakcompliance.security.sidebar-token-max-iat-age-seconds when
        // the sidebar refreshes more aggressively, or loosen it during local
        // dev. Tokens missing iat are not rejected (older Clockify schemas
        // may not include the claim).
        if (!isIatAcceptable(claims, request.getRequestURI())) {
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

    private boolean isIatAcceptable(Map<String, Object> claims, String path) {
        Instant iatInstant = iatInstant(claims.get("iat"));
        if (iatInstant == null) {
            return true; // missing iat → don't reject; honored only when present
        }
        long iat = iatInstant.getEpochSecond();
        long now = Instant.now().getEpochSecond();
        long skew = securityProps.iatClockSkewSeconds();
        long maxAge = securityProps.sidebarTokenMaxIatAgeSeconds();
        if (iat > now + skew) {
            log.debug("api.auth.iat-in-future path={} iat={} now={}", path, iat, now);
            return false;
        }
        long ageSec = now - iat;
        if (ageSec > maxAge + skew) {
            log.debug("api.auth.iat-too-old path={} ageSec={} maxAge={}", path, ageSec, maxAge);
            return false;
        }
        return true;
    }

    private static Instant iatInstant(Object value) {
        if (value instanceof Date d) {
            return d.toInstant();
        }
        if (value instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
