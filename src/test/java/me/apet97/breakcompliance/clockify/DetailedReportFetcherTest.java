package me.apet97.breakcompliance.clockify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Pins the Clockify detailed-report contract that the dev workspace and
 * production both honor:
 *
 * <ul>
 *   <li>POST {@code {reportsUrl}/v1/workspaces/{wsId}/reports/detailed} — the
 *       {@code /v1/} path segment is required.</li>
 *   <li>Body has only {@code dateRangeStart}, {@code dateRangeEnd}, and
 *       {@code detailedFilter}. No other top-level fields (notably no
 *       {@code exportType}).</li>
 *   <li>Dates are ISO_LOCAL_DATE_TIME without a timezone suffix.</li>
 *   <li>Response key is {@code timeentries} (ALL LOWERCASE — the OpenAPI
 *       spec says camelCase but live probe shows lowercase). Parser also
 *       accepts {@code timeEntries} as a defensive fallback in case
 *       Clockify ever migrates the live API to match the spec — better to
 *       quietly ingest correctly than to silently drop every entry.</li>
 * </ul>
 *
 * Each regression below corresponds to a real bug previously deployed that
 * surfaced as the dev-portal 401 the user observed.
 */
class DetailedReportFetcherTest {

    private static final String WS = "ws-test";
    private static final String REPORTS_URL = "https://reports.api.clockify.me";
    private static final String TOKEN = "install-tok";

    private ClockifyApi api;
    private DetailedReportFetcher fetcher;

    @BeforeEach
    void freshFetcher() {
        api = Mockito.mock(ClockifyApi.class);
        fetcher = new DetailedReportFetcher(api, new ObjectMapper());
    }

    private void mockResponse(String body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        org.springframework.http.ResponseEntity<String> response = new org.springframework.http.ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
        Mockito.when(api.postWithHeaders(any(), any(), any(), any(), any(), eq(String.class)))
                .thenReturn(response);
    }
    
    private void mockResponse(String body, String url) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        org.springframework.http.ResponseEntity<String> response = new org.springframework.http.ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
        Mockito.when(api.postWithHeaders(eq(WS), eq(REPORTS_URL), eq(TOKEN), anyString(), any(), eq(String.class)))
                .thenReturn(response);
    }

    @Test
    void usesV1PathPrefix() {
        mockResponse("{\"timeentries\": [], \"totals\": []}", "");

        fetcher.fetch(WS, REPORTS_URL, TOKEN,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(api).postWithHeaders(eq(WS), eq(REPORTS_URL), eq(TOKEN),
                pathCaptor.capture(), any(), eq(String.class));

        assertThat(pathCaptor.getValue()).isEqualTo("/v1/workspaces/" + WS + "/reports/detailed");
    }

    @Test
    void datesAreIsoLocalWithoutTimezoneSuffix() {
        mockResponse("{\"timeentries\": [], \"totals\": []}");

        fetcher.fetch(WS, REPORTS_URL, TOKEN,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"));

        Map<String, Object> body = captureRequestBody();

        // Clockify spec: "YYYY-MM-DDTHH:MM:SS.ssssss" — interpreted in the
        // user's timezone. A trailing 'Z' makes the server reject or
        // misinterpret the range.
        assertThat(body).containsEntry("dateRangeStart", "2026-05-01T00:00:00");
        assertThat(body).containsEntry("dateRangeEnd", "2026-05-07T23:59:59");
        assertThat(body.get("dateRangeStart").toString()).doesNotContain("Z");
        assertThat(body.get("dateRangeEnd").toString()).doesNotContain("Z");
    }

    @Test
    void bodyHasOnlyDateRangeAndDetailedFilter() {
        mockResponse("{\"timeentries\": [], \"totals\": []}");

        fetcher.fetch(WS, REPORTS_URL, TOKEN,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"));

        Map<String, Object> body = captureRequestBody();

        // Per probe-lab evidence: only detailedFilter is honored for the
        // detailed-report endpoint. exportType, summaryFilter, etc. must
        // NOT be sent.
        assertThat(body).containsOnlyKeys("dateRangeStart", "dateRangeEnd", "detailedFilter");
        assertThat(body).doesNotContainKey("exportType");

        @SuppressWarnings("unchecked")
        Map<String, Object> detailedFilter = (Map<String, Object>) body.get("detailedFilter");
        assertThat(detailedFilter).containsEntry("page", 1).containsEntry("pageSize", 200);
    }

    @Test
    void parsesTimeentriesLowercaseResponseKey() {
        mockResponse("""
                {
                  "timeentries": [
                    {"_id": "e1", "userId": "u1", "description": "work"},
                    {"_id": "e2", "userId": "u1", "description": "more work"}
                  ],
                  "totals": []
                }
                """);

        List<Map<String, Object>> entries = fetcher.fetch(
                WS, REPORTS_URL, TOKEN,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"));

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("_id", "e1").containsEntry("userId", "u1");
        assertThat(entries.get(1)).containsEntry("_id", "e2");
    }

    @Test
    void emptyTimeEntriesArrayShortCircuits() {
        mockResponse("{\"timeentries\": [], \"totals\": []}");

        List<Map<String, Object>> entries = fetcher.fetch(
                WS, REPORTS_URL, TOKEN,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"));

        assertThat(entries).isEmpty();
        // Only one page request — short-circuit when timeentries is empty.
        Mockito.verify(api, Mockito.times(1)).postWithHeaders(any(), any(), any(), any(), any(), eq(String.class));
    }

    @Test
    void fallsBackToCamelCaseTimeEntriesKeyWhenLowercaseMissing() {
        // The live API returns `timeentries` (lowercase) and the OpenAPI spec
        // labels the field as `timeEntries` (camelCase). The fetcher prefers
        // the live shape but accepts the spec shape as a fallback so a future
        // Clockify migration to match its own spec won't silently drop every
        // entry — the worst case before this fallback was "looks like the
        // workspace has no time entries, no findings, no complaint surfaced
        // to the admin."
        mockResponse("""
                {
                  "timeEntries": [
                    {"_id": "e1", "userId": "u1"},
                    {"_id": "e2", "userId": "u1"}
                  ]
                }
                """);

        List<Map<String, Object>> entries = fetcher.fetch(
                WS, REPORTS_URL, TOKEN,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"));

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("_id", "e1");
    }

    @Test
    void prefersLowercaseTimeentriesWhenBothKeysPresent() {
        // Defensive: if Clockify ever ships both keys during a transition,
        // we keep reading the lowercase one (matches live API and the
        // historical contract pinned by other tests).
        mockResponse("""
                {
                  "timeentries": [
                    {"_id": "live-1", "userId": "u1"}
                  ],
                  "timeEntries": [
                    {"_id": "spec-1", "userId": "u1"}
                  ]
                }
                """);

        List<Map<String, Object>> entries = fetcher.fetch(
                WS, REPORTS_URL, TOKEN,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-07"));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("_id", "live-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureRequestBody() {
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(api).postWithHeaders(any(), any(), any(), any(), bodyCaptor.capture(), eq(String.class));
        return (Map<String, Object>) bodyCaptor.getValue();
    }
}
