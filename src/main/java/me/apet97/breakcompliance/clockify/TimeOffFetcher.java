package me.apet97.breakcompliance.clockify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * P1.2 — fetches approved time-off requests via
 * {@code POST /v1/workspaces/{wsId}/time-off/requests} (the SEARCH variant;
 * the {@code GET} on the same path returns 405 per OpenAPI).
 *
 * <p>Engine treats every (userId, date) that overlaps an APPROVED window
 * as suppressed — same effect as a Clockify {@code type=TIME_OFF} entry,
 * but works even when the workspace doesn't auto-create entries for
 * approved requests.
 */
@Component
public class TimeOffFetcher {

    private final ClockifyApi api;
    private final ObjectMapper mapper;

    public TimeOffFetcher(ClockifyApi api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    public List<TimeOffRow> fetchApproved(
            String workspaceId, String backendUrl, String addonToken, LocalDate from, LocalDate to) {
        List<TimeOffRow> out = new ArrayList<>();
        String path = "/v1/workspaces/" + workspaceId + "/time-off/requests";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statuses", List.of("APPROVED"));
        // Bound the search to the ingest window. Clockify accepts these
        // ISO-instant fields per the search-request schema.
        body.put("start", from.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        body.put("end", to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        body.put("page", 1);
        body.put("pageSize", 200);

        String raw;
        try {
            raw = api.post(workspaceId, backendUrl, addonToken, path, body, String.class);
        } catch (ClockifyApiException e) {
            // Workspaces without the time-off feature can 404/405 here.
            return out;
        }
        if (raw == null || raw.isBlank()) return out;
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            return out;
        }
        // Response shape per OpenAPI: { requests: [ ... ] } (plural) or
        // sometimes a flat array. Accept both defensively.
        JsonNode arr = root.path("requests");
        if (!arr.isArray() && root.isArray()) arr = root;
        if (!arr.isArray()) return out;
        for (JsonNode req : arr) {
            String sourceId = textOrNull(req, "id");
            if (sourceId == null) continue;
            String userId = textOrNull(req, "userId");
            if (userId == null) continue;
            // Status comes back as a nested object { statusType, note }
            // per the live schema; flat-string is a defensive fallback for
            // any older shape Clockify might still emit.
            JsonNode statusNode = req.path("status");
            String status = statusNode.isObject()
                    ? textOrNull(statusNode, "statusType")
                    : textOrNull(req, "status");
            if (status == null || !"APPROVED".equalsIgnoreCase(status)) continue;
            // timeOffPeriod.start / .end carry ISO-instant strings.
            JsonNode period = req.path("timeOffPeriod");
            Instant startAt = parseInstant(textOrNull(period, "start"));
            Instant endAt = parseInstant(textOrNull(period, "end"));
            if (startAt == null || endAt == null) continue;
            out.add(new TimeOffRow(sourceId, userId, startAt, endAt, status));
        }
        return out;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static Instant parseInstant(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(s);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public record TimeOffRow(String sourceId, String userId, Instant startAt, Instant endAt, String status) {}
}
