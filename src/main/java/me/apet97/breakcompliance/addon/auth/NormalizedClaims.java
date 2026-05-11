package me.apet97.breakcompliance.addon.auth;

/**
 * Canonical-form Clockify JWT claims after alias resolution. The TS
 * developer-portal tokens use legacy claim names ({@code activeWs},
 * {@code apiUrl}, {@code baseURL}, {@code baseUrl}) instead of the
 * canonical names from production tokens; normalising at the boundary
 * stops every downstream service from having to know about them.
 */
public record NormalizedClaims(
        String workspaceId,
        String addonId,
        String backendUrl,
        String reportsUrl,
        String userId,
        String workspaceRole) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(workspaceRole) || "OWNER".equalsIgnoreCase(workspaceRole);
    }
}
