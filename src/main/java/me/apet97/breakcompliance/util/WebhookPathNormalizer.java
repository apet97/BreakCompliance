package me.apet97.breakcompliance.util;

import java.net.URI;

/**
 * Normalizes a webhook path entry from an {@code INSTALLED} payload into a
 * deterministic pathname for storage and lookup. Clockify may ship absolute
 * URLs ({@code https://addon.example/webhook/new-time-entry}) or paths with
 * doubled slashes from {@code baseUrl + path} concatenation (Phase 12 hit
 * this in TS); both must collapse to {@code /webhook/new-time-entry}.
 */
public final class WebhookPathNormalizer {

    private WebhookPathNormalizer() {
    }

    public static String normalize(String pathOrUrl) {
        if (pathOrUrl == null) {
            return null;
        }
        String trimmed = pathOrUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String path;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                path = URI.create(trimmed).getRawPath();
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else {
            path = trimmed;
        }
        if (path == null || path.isEmpty()) {
            return null;
        }
        String collapsed = path.replaceAll("/+", "/");
        if (!collapsed.startsWith("/")) {
            collapsed = "/" + collapsed;
        }
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }
}
