package me.apet97.breakcompliance.addon.auth;

import java.time.Instant;

/**
 * Canonical-form Clockify JWT claims after alias resolution. The TS
 * developer-portal tokens use legacy claim names ({@code activeWs},
 * {@code apiUrl}, {@code baseURL}, {@code baseUrl}) instead of the
 * canonical names from production tokens; normalising at the boundary
 * stops every downstream service from having to know about them.
 *
 * <p>{@code iat} (issued-at) is captured so lifecycle handlers can guard
 * against stale {@code DELETED} retries arriving after a fresh
 * {@code INSTALLED}: the persisted {@code installedAt} timestamp is
 * always &gt;= a fresh lifecycle event's {@code iat}, so any event whose
 * {@code iat} is older than the stored install belongs to a previous
 * incarnation and must be ignored. May be {@code null} when the upstream
 * verifier doesn't surface {@code iat} (legacy test fixtures); callers
 * decide what to do with that.
 */
public record NormalizedClaims(
        String workspaceId,
        String addonId,
        String backendUrl,
        String reportsUrl,
        String userId,
        String workspaceRole,
        Instant iat) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(workspaceRole) || "OWNER".equalsIgnoreCase(workspaceRole);
    }
}
