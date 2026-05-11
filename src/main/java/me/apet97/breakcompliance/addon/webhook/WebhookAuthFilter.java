package me.apet97.breakcompliance.addon.webhook;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import me.apet97.breakcompliance.addon.auth.ClaimsNormalizer;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.WebhookAuthToken;
import me.apet97.breakcompliance.persistence.repositories.WebhookAuthTokenRepository;
import me.apet97.breakcompliance.util.WebhookPathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Three-check verification for every webhook delivery, in order:
 *
 * <ol>
 *   <li>RS256 signature on the {@code Clockify-Signature} JWT (the SDK parser
 *       does iss/type/sub/alg/sig validation under the hood).
 *   <li>The route's request path matches a webhook the addon registered at
 *       {@code INSTALLED} time (looked up by workspaceId + addonId from the
 *       signature claims + normalised pathname).
 *   <li>The {@code authToken} claim in the signature matches the stored
 *       per-webhook {@code authToken} from the INSTALLED payload (after
 *       decryption). This protects against a valid Clockify-signed token
 *       being replayed against a different addon's webhook route.
 * </ol>
 *
 * <p>Optional 4th check: {@code clockify-webhook-event-type} header matches
 * the stored {@code eventType} for this webhook. Mismatch → 401.
 *
 * <p>Failures return 401 (signature problems) or 403 (token-mismatch /
 * unknown webhook for this workspace) with no body. On success we set the
 * verified claims on the request as {@code NORMALIZED_CLAIMS} and let the
 * controller proceed.
 */
@Component
public class WebhookAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookAuthFilter.class);
    private static final String SIGNATURE_HEADER = "Clockify-Signature";
    private static final String EVENT_TYPE_HEADER = "Clockify-Webhook-Event-Type";

    private final ClockifySignatureParser parser;
    private final WebhookAuthTokenRepository tokenRepo;
    private final TokenCodec codec;

    public WebhookAuthFilter(
            ClockifySignatureParser parser, WebhookAuthTokenRepository tokenRepo, TokenCodec codec) {
        this.parser = parser;
        this.tokenRepo = tokenRepo;
        this.codec = codec;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/webhook/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String signatureToken = firstHeader(request, SIGNATURE_HEADER);
        if (signatureToken == null) {
            log.debug("webhook.auth.missing-signature path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        Map<String, Object> claims;
        try {
            claims = parser.parseClaims(signatureToken);
        } catch (Exception e) {
            log.debug(
                    "webhook.auth.signature-failed path={} reason={}",
                    request.getRequestURI(),
                    e.getClass().getSimpleName());
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
        if (normalized.workspaceId() == null || normalized.addonId() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String normalizedPath = WebhookPathNormalizer.normalize(request.getRequestURI());
        Optional<WebhookAuthToken> stored = tokenRepo.findByWorkspaceIdAndAddonIdAndPath(
                normalized.workspaceId(), normalized.addonId(), normalizedPath);
        if (stored.isEmpty()) {
            log.debug(
                    "webhook.auth.no-stored-token workspace={} path={}",
                    normalized.workspaceId(),
                    normalizedPath);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String claimAuthToken = stringClaim(claims.get("authToken"));
        if (claimAuthToken == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String storedAuthToken;
        try {
            storedAuthToken = codec.decrypt(
                    stored.get().getAuthToken().getKeyId(),
                    stored.get().getAuthToken().getCipher());
        } catch (RuntimeException e) {
            log.warn("webhook.auth.decrypt-failed workspace={}", normalized.workspaceId());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (!storedAuthToken.equals(claimAuthToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String headerEvent = firstHeader(request, EVENT_TYPE_HEADER);
        if (headerEvent != null && !headerEvent.equalsIgnoreCase(stored.get().getEventType())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        request.setAttribute(RequestAttributes.NORMALIZED_CLAIMS, normalized);
        request.setAttribute("breakcompliance.webhook-event-type", stored.get().getEventType());
        chain.doFilter(request, response);
    }

    private static String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringClaim(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
