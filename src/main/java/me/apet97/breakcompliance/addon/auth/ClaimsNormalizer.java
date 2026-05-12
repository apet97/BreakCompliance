package me.apet97.breakcompliance.addon.auth;

import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Normalises raw Clockify JWT claims into a {@link NormalizedClaims} record.
 *
 * <p>Two pitfalls handled here:
 *
 * <ul>
 *   <li>Developer-portal tokens use legacy claim names ({@code activeWs} for
 *       workspaceId; {@code apiUrl}, {@code baseURL}, {@code baseUrl} for
 *       backendUrl). Production tokens use the canonical names. We accept
 *       either and emit canonical only.
 *   <li>The {@code backendUrl} pathname must end with {@code /api}. Some
 *       legacy tokens ship without it, with a trailing slash, or with
 *       extra path segments like {@code /api/v1}. We normalize so callers
 *       can append paths confidently.
 * </ul>
 */
public final class ClaimsNormalizer {

    private ClaimsNormalizer() {
    }

    public static NormalizedClaims normalize(Map<String, Object> claims) {
        if (claims == null) {
            throw new IllegalArgumentException("claims must not be null");
        }
        return new NormalizedClaims(
                pickString(claims, "workspaceId", "activeWs"),
                pickString(claims, "addonId"),
                normalizeBackendUrl(pickString(claims, "backendUrl", "apiUrl", "baseURL", "baseUrl")),
                pickString(claims, "reportsUrl"),
                pickString(claims, "user"),
                pickString(claims, "workspaceRole"),
                pickInstant(claims, "iat"));
    }

    /**
     * Fills missing {@code backendUrl} / {@code userId} on the already-normalised
     * JWT claims from the INSTALLED lifecycle body. The dev-portal install
     * sometimes ships a lifecycle JWT without those claims while still
     * including them in the JSON body under their legacy aliases ({@code
     * apiUrl}, {@code asUser}, {@code addonUserId}). Keeping the body-vs-claim
     * alias knowledge alongside the JWT alias knowledge so callers don't have
     * to know which side carried which field.
     */
    public static NormalizedClaims enrichFromInstalledPayload(
            NormalizedClaims claims, Map<String, Object> payload) {
        if (claims == null) {
            throw new IllegalArgumentException("claims must not be null");
        }
        if (payload == null) {
            return claims;
        }
        String backendUrl = claims.backendUrl();
        if (backendUrl == null || backendUrl.isBlank()) {
            backendUrl = normalizeBackendUrl(pickString(payload, "apiUrl"));
        }
        String userId = claims.userId();
        if (userId == null || userId.isBlank()) {
            userId = pickString(payload, "asUser", "addonUserId");
        }
        return new NormalizedClaims(
                claims.workspaceId(),
                claims.addonId(),
                backendUrl,
                claims.reportsUrl(),
                userId,
                claims.workspaceRole(),
                claims.iat());
    }

    static String normalizeBackendUrl(String raw) {
        if (raw == null) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        path = path.replaceAll("/+$", "");

        if (path.isEmpty() || "/".equals(path)) {
            path = "/api";
        } else if (path.endsWith("/api")) {
            // canonical already
        } else if (path.contains("/api/")) {
            path = path.substring(0, path.indexOf("/api/") + "/api".length());
        } else {
            path = path + "/api";
        }

        StringBuilder out = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() > 0) {
            out.append(':').append(uri.getPort());
        }
        out.append(path);
        return out.toString();
    }

    private static String pickString(Map<String, Object> claims, String... keys) {
        for (String key : keys) {
            Object value = claims.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Extract a JWT NumericDate-style claim as an {@link Instant}. JJWT
     * parses {@code iat}/{@code exp} as {@link Date}; raw maps may carry a
     * {@link Number} of seconds since epoch (RFC 7519 §2). String numerics
     * are also tolerated because lifecycle replays sometimes round-trip
     * through JSON-like text without ever rebuilding a typed claim. Returns
     * {@code null} for missing or unparseable values.
     */
    private static Instant pickInstant(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
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
