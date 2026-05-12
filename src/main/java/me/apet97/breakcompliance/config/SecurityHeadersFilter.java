package me.apet97.breakcompliance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the marketplace-required security headers to every response:
 *
 * <ul>
 *   <li><strong>CSP</strong> {@code default-src 'self'; frame-ancestors
 *       https://app.clockify.me https://*.clockify.me [+ extra-frame-ancestors]}
 *       — the iframe-embed contract Clockify enforces. The wildcard in the
 *       default ancestor list covers regional subdomains and
 *       {@code https://developer.clockify.me} so the dev-portal validator
 *       can iframe-preview components without extra configuration. Operators
 *       can extend with non-{@code .clockify.me} hosts via the
 *       {@code EXTRA_FRAME_ANCESTORS} env var (comma-delimited).
 *   <li><strong>X-Content-Type-Options</strong> {@code nosniff}.
 *   <li><strong>Referrer-Policy</strong> {@code no-referrer} — never leak
 *       the addon URL to outbound links from the iframe.
 *   <li><strong>Permissions-Policy</strong> denies camera, microphone,
 *       geolocation; we don't need any of them.
 *   <li><strong>HSTS</strong> {@code max-age=63072000; includeSubDomains}
 *       toggled on by {@code breakcompliance.security.enable-hsts}, AND
 *       only emitted when the request scheme is HTTPS — Railway terminates
 *       TLS and forwards the original scheme via {@code X-Forwarded-Proto},
 *       which Spring's {@code server.forward-headers-strategy=framework}
 *       reflects through {@link HttpServletRequest#isSecure()}. Without
 *       this guard, accidentally serving HSTS on a plain-HTTP request
 *       would pin the browser to HTTPS for years against a host that
 *       might not yet have its cert provisioned.
 *   <li><strong>Cache-Control</strong> {@code no-store} on all API responses
 *       (paths under {@code /api/*}) so workspace data is never cached by
 *       intermediaries.
 * </ul>
 */
@Component
@EnableConfigurationProperties(BreakComplianceSecurityProperties.class)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private final BreakComplianceSecurityProperties props;

    public SecurityHeadersFilter(BreakComplianceSecurityProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", buildCsp());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (props.enableHsts() && request.isSecure()) {
            // Two-year max-age + subdomains matches Railway's apex
            // certificate (covers `*.up.railway.app`). Pre-load not
            // requested — submission requires the host be in the HSTS
            // preload list and a manual review, which is out of scope.
            response.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
        }
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }

    private String buildCsp() {
        List<String> ancestors = new ArrayList<>();
        ancestors.add("https://app.clockify.me");
        ancestors.add("https://*.clockify.me");
        for (String extra : props.extraFrameAncestors()) {
            if (extra != null && !extra.isBlank()) {
                ancestors.add(extra);
            }
        }
        return "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self' 'unsafe-inline' https://resources.developer.clockify.me; "
                + "img-src 'self' data: https://resources.developer.clockify.me; "
                + "font-src 'self' https://resources.developer.clockify.me; "
                + "connect-src 'self' https://*.clockify.me; "
                + "frame-ancestors " + String.join(" ", ancestors);
    }
}
