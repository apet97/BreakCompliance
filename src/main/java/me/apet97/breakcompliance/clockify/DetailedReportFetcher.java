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
 * <p><b>Response key:</b> {@code timeEntries} (camelCase). The previous
 * {@code timeentries} (all-lowercase) silently returned an empty result on
 * every page.
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
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        while (page <= MAX_PAGES) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dateRangeStart", from.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            body.put("dateRangeEnd", to.atTime(23, 59, 59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            body.put("detailedFilter", Map.of("page", page, "pageSize", PAGE_SIZE));

            String path = "/v1/workspaces/" + workspaceId + "/reports/detailed";
            String raw = api.post(workspaceId, reportsUrl, addonToken, path, body, String.class);
            if (raw == null || raw.isBlank()) {
                break;
            }
            JsonNode root;
            try {
                root = mapper.readTree(raw);
            } catch (Exception e) {
                throw new ClockifyApiException("failed to parse detailed report", 0, e);
            }
            JsonNode entries = root.path("timeEntries");
            if (!entries.isArray() || entries.isEmpty()) {
                break;
            }
            for (JsonNode entry : entries) {
                all.add(mapper.convertValue(entry, Map.class));
            }
            if (entries.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return all;
    }
}
