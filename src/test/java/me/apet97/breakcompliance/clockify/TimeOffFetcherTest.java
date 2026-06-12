package me.apet97.breakcompliance.clockify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Pins the {@code POST /v1/workspaces/{ws}/time-off/requests} (search
 * variant) contract observed live on 2026-05-13. Two response-shape
 * details are regression-critical because they each masked a 100%
 * failure mode while the parser was wrong:
 *
 * <ol>
 *   <li>{@code status} is a nested object {@code {statusType, note, …}},
 *       not a flat string. Reading {@code req.path("status").asText()}
 *       gave the empty string and every row was discarded as
 *       non-APPROVED.</li>
 *   <li>{@code timeOffPeriod} wraps the covered window one level deeper
 *       in {@code timeOffPeriod.period.{start, end}}. Reading
 *       {@code timeOffPeriod.start} returned a missing node and every
 *       row was discarded for "no start/end."</li>
 * </ol>
 *
 * @see docs/api-calls.md §1b
 */
class TimeOffFetcherTest {

    private static final String WS = "ws-test";
    private static final String BACKEND_URL = "https://api.clockify.me";
    private static final String TOKEN = "install-tok";

    private ClockifyApi api;
    private TimeOffFetcher fetcher;

    @BeforeEach
    void freshFetcher() {
        api = Mockito.mock(ClockifyApi.class);
        fetcher = new TimeOffFetcher(api, new ObjectMapper());
    }

    private void mockResponse(String body) {
        Mockito.when(api.post(eq(WS), eq(BACKEND_URL), eq(TOKEN), anyString(), any(), eq(String.class)))
                .thenReturn(body);
    }

    private void mockResponses(String first, String... rest) {
        Mockito.when(api.post(eq(WS), eq(BACKEND_URL), eq(TOKEN), anyString(), any(), eq(String.class)))
                .thenReturn(first, rest);
    }

    @Test
    void postsSearchBodyWithApprovedStatusFilter() {
        mockResponse("{\"count\":0,\"requests\":[]}");

        fetcher.fetchApproved(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(api).post(eq(WS), eq(BACKEND_URL), eq(TOKEN),
                eq("/v1/workspaces/" + WS + "/time-off/requests"),
                bodyCaptor.capture(), eq(String.class));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertThat(body).containsEntry("statuses", List.of("APPROVED"));
        assertThat(body).containsKeys("start", "end", "page", "pageSize");
    }

    @Test
    void parsesNestedStatusObjectAndPeriod_liveShape() {
        // Verbatim slice of the 2026-05-13 sacrificial-workspace probe.
        mockResponse("""
                {
                  "count": 1,
                  "requests": [
                    {
                      "id": "6a03a4a52568d3d29336df75",
                      "userId": "64621faec4d2cc53b91fce6c",
                      "timeOffPeriod": {
                        "period": {
                          "start": "2026-12-20T23:00:00Z",
                          "end":   "2026-12-21T22:59:59Z"
                        },
                        "halfDay": false
                      },
                      "status": {
                        "statusType": "APPROVED",
                        "note": null
                      }
                    }
                  ]
                }
                """);

        List<TimeOffFetcher.TimeOffRow> out = fetcher.fetchApproved(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        assertThat(out).hasSize(1);
        TimeOffFetcher.TimeOffRow row = out.get(0);
        assertThat(row.sourceId()).isEqualTo("6a03a4a52568d3d29336df75");
        assertThat(row.userId()).isEqualTo("64621faec4d2cc53b91fce6c");
        assertThat(row.status()).isEqualTo("APPROVED");
        assertThat(row.startAt()).isEqualTo(Instant.parse("2026-12-20T23:00:00Z"));
        assertThat(row.endAt()).isEqualTo(Instant.parse("2026-12-21T22:59:59Z"));
    }

    @Test
    void filtersOutNonApprovedStatuses() {
        // Workspace might echo PENDING / REJECTED requests if the search
        // is loose; the engine should only consider APPROVED ones.
        mockResponse("""
                {
                  "requests": [
                    { "id": "p1", "userId": "u1",
                      "status": { "statusType": "PENDING" },
                      "timeOffPeriod": { "period": { "start": "2026-12-20T00:00:00Z", "end": "2026-12-21T00:00:00Z" } } },
                    { "id": "p2", "userId": "u1",
                      "status": { "statusType": "REJECTED" },
                      "timeOffPeriod": { "period": { "start": "2026-12-22T00:00:00Z", "end": "2026-12-23T00:00:00Z" } } },
                    { "id": "p3", "userId": "u2",
                      "status": { "statusType": "APPROVED" },
                      "timeOffPeriod": { "period": { "start": "2026-12-24T00:00:00Z", "end": "2026-12-25T00:00:00Z" } } }
                  ]
                }
                """);

        List<TimeOffFetcher.TimeOffRow> out = fetcher.fetchApproved(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).sourceId()).isEqualTo("p3");
    }

    @Test
    void flatStatusString_acceptedAsDefensiveFallback() {
        // If Clockify ever flattens the shape, the parser's secondary
        // branch keeps us working — better than silently dropping rows.
        mockResponse("""
                {
                  "requests": [
                    { "id": "x", "userId": "u",
                      "status": "APPROVED",
                      "timeOffPeriod": { "period": { "start": "2026-12-20T00:00:00Z", "end": "2026-12-21T00:00:00Z" } } }
                  ]
                }
                """);

        List<TimeOffFetcher.TimeOffRow> out = fetcher.fetchApproved(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).status()).isEqualTo("APPROVED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void paginatesBeyondFirstTwoHundredRows() {
        mockResponses(requestsPayload(0, 200, 201), requestsPayload(200, 1, 201));

        List<TimeOffFetcher.TimeOffRow> out = fetcher.fetchApproved(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        assertThat(out).hasSize(201);
        assertThat(out.get(0).sourceId()).isEqualTo("pto-0");
        assertThat(out.get(200).sourceId()).isEqualTo("pto-200");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(api, Mockito.times(2)).post(eq(WS), eq(BACKEND_URL), eq(TOKEN),
                eq("/v1/workspaces/" + WS + "/time-off/requests"),
                bodyCaptor.capture(), eq(String.class));

        List<Map<String, Object>> pages = bodyCaptor.getAllValues().stream()
                .map(value -> (Map<String, Object>) value)
                .toList();
        assertThat(pages).extracting(page -> page.get("page")).containsExactly(1, 2);
        assertThat(pages).extracting(page -> page.get("pageSize")).containsOnly(200);
    }

    @Test
    void apiException_returnsEmptyListNotThrow() {
        Mockito.when(api.post(any(), any(), any(), any(), any(), eq(String.class)))
                .thenThrow(new ClockifyApiException("workspace has no time-off feature", 404, null));

        List<TimeOffFetcher.TimeOffRow> out = fetcher.fetchApproved(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"));

        assertThat(out).isEmpty();
    }

    private static String requestsPayload(int startIndex, int count, int totalCount) {
        StringBuilder json = new StringBuilder();
        json.append("{\"count\":").append(totalCount).append(",\"requests\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            int id = startIndex + i;
            json.append("""
                    {"id":"pto-%d","userId":"u-%d","status":{"statusType":"APPROVED"},"timeOffPeriod":{"period":{"start":"2026-12-20T00:00:00Z","end":"2026-12-21T00:00:00Z"}}}
                    """.formatted(id, id));
        }
        json.append("]}");
        return json.toString();
    }
}
