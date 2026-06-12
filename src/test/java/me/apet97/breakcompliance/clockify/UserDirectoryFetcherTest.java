package me.apet97.breakcompliance.clockify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pins the {@code GET /v1/workspaces/{ws}/users} contract observed live
 * on 2026-05-13. Used by the userName reconciler in
 * {@code IngestionService.refreshSuppressionForWindow}.
 */
class UserDirectoryFetcherTest {

    private static final String WS = "ws-test";
    private static final String BACKEND_URL = "https://api.clockify.me";
    private static final String TOKEN = "install-tok";

    private ClockifyApi api;
    private UserDirectoryFetcher fetcher;

    @BeforeEach
    void freshFetcher() {
        api = Mockito.mock(ClockifyApi.class);
        fetcher = new UserDirectoryFetcher(api, new ObjectMapper());
    }

    private void mockResponse(String body) {
        Mockito.when(api.get(eq(WS), eq(BACKEND_URL), eq(TOKEN), anyString(), eq(String.class)))
                .thenReturn(body);
    }

    private void mockResponses(String first, String... rest) {
        Mockito.when(api.get(eq(WS), eq(BACKEND_URL), eq(TOKEN), anyString(), eq(String.class)))
                .thenReturn(first, rest);
    }

    @Test
    void hitsActiveUsersEndpointWithPageSize200() {
        mockResponse("[]");

        fetcher.fetchActive(WS, BACKEND_URL, TOKEN);

        Mockito.verify(api).get(eq(WS), eq(BACKEND_URL), eq(TOKEN),
                eq("/v1/workspaces/" + WS + "/users?status=ACTIVE&page=1&page-size=200"),
                eq(String.class));
    }

    @Test
    void parsesIdAndName() {
        mockResponse("""
                [
                  {"id": "u1", "name": "Alice",          "email": "alice@example.com", "status": "ACTIVE"},
                  {"id": "u2", "name": "Firstname Last", "email": "fl@example.com",    "status": "ACTIVE"}
                ]
                """);

        Map<String, String> directory = fetcher.fetchActive(WS, BACKEND_URL, TOKEN);

        assertThat(directory).containsEntry("u1", "Alice");
        assertThat(directory).containsEntry("u2", "Firstname Last");
    }

    @Test
    void fallsBackToEmailWhenNameBlank() {
        // Some workspaces have users without a display name set — fall
        // back to email so the sidebar doesn't render bare userIds.
        mockResponse("""
                [
                  {"id": "u1", "name": "",   "email": "alice@example.com"},
                  {"id": "u2", "name": null, "email": "bob@example.com"}
                ]
                """);

        Map<String, String> directory = fetcher.fetchActive(WS, BACKEND_URL, TOKEN);

        assertThat(directory).containsEntry("u1", "alice@example.com");
        assertThat(directory).containsEntry("u2", "bob@example.com");
    }

    @Test
    void skipsUsersWithoutIdOrAnyLabel() {
        mockResponse("""
                [
                  {"id": "u1", "name": "Alice"},
                  {"name": "Bob"},
                  {"id": "u3"},
                  {"id": "u4", "name": "Dora"}
                ]
                """);

        Map<String, String> directory = fetcher.fetchActive(WS, BACKEND_URL, TOKEN);

        // u2 (no id) and u3 (no name + no email) drop; u1 + u4 remain.
        assertThat(directory).containsOnlyKeys("u1", "u4");
    }

    @Test
    void paginatesBeyondFirstTwoHundredUsers() {
        mockResponses(usersPayload(0, 200), usersPayload(200, 1));

        Map<String, String> directory = fetcher.fetchActive(WS, BACKEND_URL, TOKEN);

        assertThat(directory).hasSize(201);
        assertThat(directory).containsEntry("u-0", "User 0");
        assertThat(directory).containsEntry("u-200", "User 200");
        Mockito.verify(api).get(eq(WS), eq(BACKEND_URL), eq(TOKEN),
                eq("/v1/workspaces/" + WS + "/users?status=ACTIVE&page=1&page-size=200"),
                eq(String.class));
        Mockito.verify(api).get(eq(WS), eq(BACKEND_URL), eq(TOKEN),
                eq("/v1/workspaces/" + WS + "/users?status=ACTIVE&page=2&page-size=200"),
                eq(String.class));
    }

    @Test
    void apiException_returnsEmptyMap() {
        Mockito.when(api.get(any(), any(), any(), any(), any()))
                .thenThrow(new ClockifyApiException("not allowed", 403, null));

        Map<String, String> directory = fetcher.fetchActive(WS, BACKEND_URL, TOKEN);

        assertThat(directory).isEmpty();
    }

    private static String usersPayload(int startIndex, int count) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            int id = startIndex + i;
            json.append("{\"id\":\"u-").append(id).append("\",\"name\":\"User ")
                    .append(id).append("\",\"email\":\"user").append(id).append("@example.com\"}");
        }
        json.append(']');
        return json.toString();
    }
}
