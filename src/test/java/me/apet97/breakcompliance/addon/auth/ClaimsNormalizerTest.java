package me.apet97.breakcompliance.addon.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaimsNormalizerTest {

    @Test
    void picksCanonicalWorkspaceIdWhenPresent() {
        NormalizedClaims n = ClaimsNormalizer.normalize(Map.of(
                "workspaceId", "ws-canonical",
                "activeWs", "ws-legacy",
                "addonId", "a1",
                "backendUrl", "https://api.clockify.me/api"));

        assertThat(n.workspaceId()).isEqualTo("ws-canonical");
    }

    @Test
    void fallsBackToActiveWsForWorkspaceId() {
        NormalizedClaims n = ClaimsNormalizer.normalize(Map.of(
                "activeWs", "ws-legacy",
                "addonId", "a1",
                "backendUrl", "https://api.clockify.me/api"));

        assertThat(n.workspaceId()).isEqualTo("ws-legacy");
    }

    @Test
    void canonicalBackendUrlWins() {
        NormalizedClaims n = ClaimsNormalizer.normalize(Map.of(
                "workspaceId", "ws-1",
                "backendUrl", "https://api.clockify.me/api",
                "apiUrl", "https://stale.example/api"));

        assertThat(n.backendUrl()).isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void fallsBackThroughApiUrlBaseURLBaseUrl() {
        NormalizedClaims a = ClaimsNormalizer.normalize(Map.of(
                "workspaceId", "ws-1", "apiUrl", "https://api.clockify.me/api"));
        NormalizedClaims b = ClaimsNormalizer.normalize(Map.of(
                "workspaceId", "ws-1", "baseURL", "https://api.clockify.me/api"));
        NormalizedClaims c = ClaimsNormalizer.normalize(Map.of(
                "workspaceId", "ws-1", "baseUrl", "https://api.clockify.me/api"));

        assertThat(a.backendUrl()).isEqualTo("https://api.clockify.me/api");
        assertThat(b.backendUrl()).isEqualTo("https://api.clockify.me/api");
        assertThat(c.backendUrl()).isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void backendUrlPathnameAlwaysEndsWithApi() {
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://api.clockify.me"))
                .isEqualTo("https://api.clockify.me/api");
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://api.clockify.me/"))
                .isEqualTo("https://api.clockify.me/api");
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://api.clockify.me/api"))
                .isEqualTo("https://api.clockify.me/api");
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://api.clockify.me/api/"))
                .isEqualTo("https://api.clockify.me/api");
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://api.clockify.me/api/v1"))
                .isEqualTo("https://api.clockify.me/api");
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://api.clockify.me/api/v1/workspaces/123"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void normalizesEuRegion() {
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://eu.api.clockify.me"))
                .isEqualTo("https://eu.api.clockify.me/api");
    }

    @Test
    void normalizesDeveloperPortalHost() {
        assertThat(ClaimsNormalizer.normalizeBackendUrl("https://developer.clockify.me"))
                .isEqualTo("https://developer.clockify.me/api");
    }

    @Test
    void preservesNonStandardPort() {
        assertThat(ClaimsNormalizer.normalizeBackendUrl("http://localhost:8787/api"))
                .isEqualTo("http://localhost:8787/api");
        assertThat(ClaimsNormalizer.normalizeBackendUrl("http://localhost:8787"))
                .isEqualTo("http://localhost:8787/api");
    }

    @Test
    void rejectsMalformedUrlsByReturningNull() {
        assertThat(ClaimsNormalizer.normalizeBackendUrl("not-a-url")).isNull();
        assertThat(ClaimsNormalizer.normalizeBackendUrl("ftp://no-host")).isNull();
    }

    @Test
    void readsUserIdFromUserClaimNotUserId() {
        NormalizedClaims n = ClaimsNormalizer.normalize(Map.of(
                "workspaceId", "ws-1",
                "user", "user-42",
                "userId", "ignored-non-canonical"));

        assertThat(n.userId()).isEqualTo("user-42");
    }

    @Test
    void capturesWorkspaceRoleAndAddonIdAndReportsUrl() {
        NormalizedClaims n = ClaimsNormalizer.normalize(Map.of(
                "workspaceId", "ws-1",
                "addonId", "69e81390556e8f94308aaad8",
                "workspaceRole", "WORKSPACE_ADMIN",
                "reportsUrl", "https://reports.api.clockify.me",
                "backendUrl", "https://api.clockify.me/api"));

        assertThat(n.addonId()).isEqualTo("69e81390556e8f94308aaad8");
        assertThat(n.workspaceRole()).isEqualTo("WORKSPACE_ADMIN");
        assertThat(n.reportsUrl()).isEqualTo("https://reports.api.clockify.me");
    }

    @Test
    void blankStringsAreTreatedAsAbsent() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("workspaceId", "   ");
        claims.put("activeWs", "ws-fallback");

        NormalizedClaims n = ClaimsNormalizer.normalize(claims);
        assertThat(n.workspaceId()).isEqualTo("ws-fallback");
    }

    @Test
    void nullClaimsMapRejected() {
        assertThatThrownBy(() -> ClaimsNormalizer.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
