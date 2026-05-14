package me.apet97.breakcompliance.clockify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fetches the Clockify Detailed Report for a workspace + date range, walking
 * pagination until empty. Uses POST
 * {@code {reportsUrl}/v1/workspaces/{wsId}/reports/detailed} — the canonical
 * marketplace endpoint per
 * {@code Cldocs/01-canonical-docs/build/environments-and-regions.md} and
 * the live-probed shape in {@code clockify-api-probe-lab/ATTENDANCEANDTIMEREPORTS.md}.
 *
 * <p><b>Request body shape:</b> only {@code dateRangeStart}, {@code dateRangeEnd},
 * and {@code detailedFilter} (page + pageSize). Other report-type sub-filters
 * (summaryFilter, weeklyFilter, attendanceFilter) are ignored for this endpoint.
 * Dates are sent as {@code yyyy-MM-dd'T'HH:mm:ss} (no timezone suffix); the
 * server interprets them in the user's timezone per the Clockify spec.
 *
 * <p><b>Response key:</b> {@code timeentries} (ALL LOWERCASE). The OpenAPI
 * spec at {@code clockify-api-probe-lab/ATTENDANCEANDTIMEREPORTS.md:885}
 * labels the field as {@code timeEntries} (camelCase), but a live probe on
 * 2026-05-11 against {@code reports.api.clockify.me} with a valid X-Api-Key
 * returned {@code "timeentries"} (along with {@code "totals"}). The spec is
 * wrong; the live API is right. We still accept {@code timeEntries} as a
 * defensive fallback — if Clockify ever migrates the live API to match the
 * spec, the failure mode without this fallback would be silent (the parser
 * would see {@code timeentries} as missing → return zero entries → "looks
 * like an empty workspace" with no error surfaced).
 */
@Component
public class DetailedReportFetcher {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 500;

    private final ClockifyApi api;
    private final ObjectMapper mapper;

    public DetailedReportFetcher(ClockifyApi api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> fetch(
            String workspaceId, String reportsUrl, String addonToken, LocalDate from, LocalDate to) {
        // Defaults to the historical "fetch every entry" behaviour. P1.3
        // callers go through fetch(..., excludeUnsubmittedEntries=true) which
        // adds approvalState=APPROVED to the body.
        return fetch(workspaceId, reportsUrl, addonToken, from, to, false);
    }

    public List<Map<String, Object>> fetch(
            String workspaceId,
            String reportsUrl,
            String addonToken,
            LocalDate from,
            LocalDate to,
            boolean excludeUnsubmittedEntries) {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        while (page <= MAX_PAGES) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dateRangeStart", from.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            body.put("dateRangeEnd", to.atTime(23, 59, 59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            body.put("detailedFilter", Map.of("page", page, "pageSize", PAGE_SIZE));
            if (excludeUnsubmittedEntries) {
                // P1.3 — Clockify honours top-level approvalState on the
                // detailed-report request body. With APPROVED, the response
                // omits everything still in WIP / SUBMITTED states, which
                // means findings only fire for entries the user has
                // confirmed are real.
                body.put("approvalState", "APPROVED");
            }

            String path = "/v1/workspaces/" + workspaceId + "/reports/detailed";
            org.springframework.http.ResponseEntity<String> response = api.postWithHeaders(workspaceId, reportsUrl, addonToken, path, body, String.class);
            String raw = response.getBody();
            if (raw == null || raw.isBlank()) {
                break;
            }
            JsonNode root;
            try {
                root = mapper.readTree(raw);
            } catch (Exception e) {
                throw new ClockifyApiException("failed to parse detailed report", 0, e);
            }
            JsonNode entries = entriesNode(root);
            if (!entries.isArray() || entries.isEmpty()) {
                break;
            }
            for (JsonNode entry : entries) {
                all.add(mapper.convertValue(entry, Map.class));
            }
            
            // Pagination stop conditions, in order of preference:
            //   1. Clockify sends `Last-Page: true` when it knows this is the
            //      final page. This is the canonical contract per the
            //      marketplace docs and is set on a strict majority of
            //      responses.
            //   2. Some legacy / regional builds omit the header on the final
            //      page; if the returned entry count is below PAGE_SIZE we
            //      treat that as a soft terminator. This is safe because
            //      Clockify always fills a page when there are more rows
            //      available — `< PAGE_SIZE` implies "nothing more".
            //   3. MAX_PAGES is the hard safety net so a runaway loop can
            //      never burn a worker thread indefinitely.
            String lastPageStr = response.getHeaders().getFirst("Last-Page");
            // HTTP header values are case-insensitive (RFC 7230 §3.2.4) — some
            // regional builds return "True" / "TRUE"; treat them all as the
            // canonical terminator.
            if (lastPageStr != null && lastPageStr.equalsIgnoreCase("true")) {
                break;
            }
            if (entries.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return all;
    }

    /**
     * Read the entries array out of a detailed-report response, accepting
     * both the live API's {@code timeentries} key and the OpenAPI spec's
     * {@code timeEntries} key. Prefers the live shape — a populated
     * lowercase array always wins, even if a camelCase array is also
     * present. Returns whichever node was actually keyed in the JSON when
     * neither array contains rows, so the empty-page short-circuit at the
     * call site still trips.
     */
    private static JsonNode entriesNode(JsonNode root) {
        JsonNode lower = root.path("timeentries");
        if (lower.isArray() && !lower.isEmpty()) {
            return lower;
        }
        JsonNode camel = root.path("timeEntries");
        if (camel.isArray() && !camel.isEmpty()) {
            return camel;
        }
        return lower.isMissingNode() ? camel : lower;
    }
}
