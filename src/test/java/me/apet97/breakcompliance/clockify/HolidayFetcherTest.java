package me.apet97.breakcompliance.clockify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pins the {@code GET /v1/workspaces/{ws}/holidays} contract observed
 * live on 2026-05-13 against the sacrificial workspace. The
 * non-{@code /in-period} variant is chosen because the
 * {@code /in-period} endpoint rejects requests without an
 * {@code assigned-to} ObjectId (live probe + {@code docs/api-calls.md}
 * §1a).
 */
class HolidayFetcherTest {

    private static final String WS = "ws-test";
    private static final String BACKEND_URL = "https://api.clockify.me";
    private static final String TOKEN = "install-tok";

    private ClockifyApi api;
    private HolidayFetcher fetcher;

    @BeforeEach
    void freshFetcher() {
        api = Mockito.mock(ClockifyApi.class);
        fetcher = new HolidayFetcher(api, new ObjectMapper());
    }

    private void mockResponse(String body) {
        Mockito.when(api.get(eq(WS), eq(BACKEND_URL), eq(TOKEN), anyString(), eq(String.class)))
                .thenReturn(body);
    }

    @Test
    void hitsNonInPeriodEndpoint() {
        mockResponse("[]");

        fetcher.fetch(WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        // Path must be the un-filtered /holidays — the /in-period variant
        // returns HTTP 4xx without an assigned-to ObjectId (live probe).
        Mockito.verify(api).get(eq(WS), eq(BACKEND_URL), eq(TOKEN),
                eq("/v1/workspaces/" + WS + "/holidays"), eq(String.class));
    }

    @Test
    void everyoneIncludingNew_emitsWorkspaceWideRow() {
        mockResponse("""
                [
                  {
                    "id": "h-xmas",
                    "name": "Christmas",
                    "userIds": ["u1", "u2"],
                    "everyoneIncludingNew": true,
                    "datePeriod": { "startDate": "2026-12-25", "endDate": "2026-12-25" }
                  }
                ]
                """);

        List<HolidayFetcher.HolidayRow> out = fetcher.fetch(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        // everyoneIncludingNew=true wins over the userIds array — one row
        // with appliesToUserId=null suppresses every user's bucket.
        assertThat(out).hasSize(1);
        assertThat(out.get(0).appliesToUserId()).isNull();
        assertThat(out.get(0).date()).isEqualTo(LocalDate.parse("2026-12-25"));
        assertThat(out.get(0).name()).isEqualTo("Christmas");
        assertThat(out.get(0).sourceId()).isEqualTo("h-xmas");
    }

    @Test
    void userSpecific_emitsOneRowPerUser() {
        mockResponse("""
                [
                  {
                    "id": "h-user",
                    "name": "User-only holiday",
                    "userIds": ["u1", "u2"],
                    "everyoneIncludingNew": false,
                    "datePeriod": { "startDate": "2026-12-10", "endDate": "2026-12-10" }
                  }
                ]
                """);

        List<HolidayFetcher.HolidayRow> out = fetcher.fetch(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        assertThat(out).hasSize(2);
        assertThat(out).extracting(HolidayFetcher.HolidayRow::appliesToUserId)
                .containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void multiDaySpan_iteratesEveryDay() {
        mockResponse("""
                [
                  {
                    "id": "h-week",
                    "name": "Holiday week",
                    "everyoneIncludingNew": true,
                    "datePeriod": { "startDate": "2026-12-20", "endDate": "2026-12-22" }
                  }
                ]
                """);

        List<HolidayFetcher.HolidayRow> out = fetcher.fetch(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        assertThat(out).hasSize(3);
        assertThat(out).extracting(HolidayFetcher.HolidayRow::date)
                .containsExactly(
                        LocalDate.parse("2026-12-20"),
                        LocalDate.parse("2026-12-21"),
                        LocalDate.parse("2026-12-22"));
    }

    @Test
    void spanClampedToRequestedWindow() {
        // Holiday spans both before AND after the requested window — only
        // the dates inside [from, to] should make it into the output.
        mockResponse("""
                [
                  {
                    "id": "h-long",
                    "name": "Annual closure",
                    "everyoneIncludingNew": true,
                    "datePeriod": { "startDate": "2026-12-22", "endDate": "2027-01-03" }
                  }
                ]
                """);

        List<HolidayFetcher.HolidayRow> out = fetcher.fetch(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-27"));

        assertThat(out).hasSize(3);
        assertThat(out).extracting(HolidayFetcher.HolidayRow::date)
                .containsExactly(
                        LocalDate.parse("2026-12-25"),
                        LocalDate.parse("2026-12-26"),
                        LocalDate.parse("2026-12-27"));
    }

    @Test
    void apiException_returnsEmptyListNotThrow() {
        Mockito.when(api.get(any(), any(), any(), any(), any()))
                .thenThrow(new ClockifyApiException("scope not granted", 403, null));

        List<HolidayFetcher.HolidayRow> out = fetcher.fetch(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        // Permission-gated 401/403 stays best-effort — empty list lets
        // the ingest continue without suppression.
        assertThat(out).isEmpty();
    }

    @Test
    void malformedPayload_returnsEmptyList() {
        mockResponse("not json");

        List<HolidayFetcher.HolidayRow> out = fetcher.fetch(
                WS, BACKEND_URL, TOKEN, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));

        assertThat(out).isEmpty();
    }
}
