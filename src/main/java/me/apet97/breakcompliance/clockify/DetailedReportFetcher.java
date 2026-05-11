package me.apet97.breakcompliance.clockify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fetches the Clockify Detailed Report for a workspace + date range, walking
 * pagination until empty. Uses POST {@code /workspaces/{wsId}/reports/detailed}
 * — the documented marketplace endpoint for bulk time-entry retrieval.
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
            body.put("dateRangeStart", from.atStartOfDay() + "Z");
            body.put("dateRangeEnd", to.atTime(23, 59, 59) + "Z");
            body.put("detailedFilter", Map.of("page", page, "pageSize", PAGE_SIZE));
            body.put("exportType", "JSON");

            String path = "/workspaces/" + workspaceId + "/reports/detailed";
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
            JsonNode entries = root.path("timeentries");
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
