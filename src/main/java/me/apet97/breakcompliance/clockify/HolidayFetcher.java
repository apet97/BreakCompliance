package me.apet97.breakcompliance.clockify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * P1.1 — fetches workspace holidays in a date range from
 * {@code GET /v1/workspaces/{wsId}/holidays/in-period?start=&end=}.
 *
 * <p>Response shape (live, dev portal 2026-05): an array of holiday
 * objects, each carrying {@code id}, {@code name}, a
 * {@code datePeriod.startDate} + {@code datePeriod.endDate} (yyyy-MM-dd),
 * and either {@code userIds} (per-user assignment) or
 * {@code everyoneIncludingNew=true} (workspace-wide).
 *
 * <p>This is best-effort. Any error returns an empty list — the worst
 * outcome is "no suppression," which matches the historical engine
 * behaviour before P1.1 was added.
 */
@Component
public class HolidayFetcher {

    private final ClockifyApi api;
    private final ObjectMapper mapper;

    public HolidayFetcher(ClockifyApi api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * @return list of (date, userId-or-null, name, sourceId) tuples
     *         covering every day in {@code [from, to]}.
     *
     *         <p>Uses {@code GET /workspaces/{ws}/holidays} (full list) and
     *         filters by date client-side. The {@code /in-period} variant
     *         requires an {@code assigned-to} ObjectId param even though
     *         OpenAPI marks it optional (live probe 2026-05-13). The
     *         plain endpoint returns the same data without the per-user
     *         filter and lets us serve workspace-wide + user-specific
     *         holidays from one call.
     */
    public List<HolidayRow> fetch(
            String workspaceId, String backendUrl, String addonToken, LocalDate from, LocalDate to) {
        List<HolidayRow> out = new ArrayList<>();
        String path = "/v1/workspaces/" + workspaceId + "/holidays";
        String raw;
        try {
            raw = api.get(workspaceId, backendUrl, addonToken, path, String.class);
        } catch (ClockifyApiException e) {
            // Permission-gated routes can 401/403 for workspaces that
            // haven't granted us the holiday read. Don't fail the ingest.
            return out;
        }
        if (raw == null || raw.isBlank()) return out;
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            return out;
        }
        if (!root.isArray()) return out;
        for (JsonNode holiday : root) {
            String sourceId = textOrNull(holiday, "id");
            if (sourceId == null) continue;
            String name = textOrNull(holiday, "name");
            JsonNode period = holiday.path("datePeriod");
            LocalDate startDate = parseDate(textOrNull(period, "startDate"));
            LocalDate endDate = parseDate(textOrNull(period, "endDate"));
            if (startDate == null) continue;
            if (endDate == null) endDate = startDate;
            // Trim the (startDate, endDate) span to the requested window
            // before iterating, so we don't emit rows outside [from, to].
            LocalDate effStart = startDate.isBefore(from) ? from : startDate;
            LocalDate effEnd = endDate.isAfter(to) ? to : endDate;
            if (effStart.isAfter(effEnd)) continue;
            List<String> userIds = collectUserIds(holiday);
            for (LocalDate d = effStart; !d.isAfter(effEnd); d = d.plusDays(1)) {
                if (userIds.isEmpty()) {
                    // Workspace-wide holiday — record as appliesToUserId=null.
                    out.add(new HolidayRow(sourceId, d, null, name));
                } else {
                    for (String userId : userIds) {
                        out.add(new HolidayRow(sourceId, d, userId, name));
                    }
                }
            }
        }
        return out;
    }

    private static List<String> collectUserIds(JsonNode holiday) {
        // everyoneIncludingNew=true ⇒ workspace-wide, ignore userIds.
        if (holiday.path("everyoneIncludingNew").asBoolean(false)) {
            return List.of();
        }
        JsonNode arr = holiday.path("userIds");
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText());
        }
        return out;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    public record HolidayRow(String sourceId, LocalDate date, String appliesToUserId, String name) {}
}
