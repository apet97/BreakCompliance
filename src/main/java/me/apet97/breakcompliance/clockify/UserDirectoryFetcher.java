package me.apet97.breakcompliance.clockify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * P2.3 — fetches the workspace's active users so the reconciler can
 * refresh stale {@code userName} columns on time entries.
 *
 * <p>Single page of up to 200 users — sufficient for the typical
 * workspace. Workspaces with more users get the first page (sorted by
 * NAME default) and the remainder will reconcile naturally as future
 * ingest runs touch them.
 */
@Component
public class UserDirectoryFetcher {

    private static final int PAGE_SIZE = 200;

    private final ClockifyApi api;
    private final ObjectMapper mapper;

    public UserDirectoryFetcher(ClockifyApi api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * @return map of userId → displayName (best-effort: empty map on any
     *         API or parse error so callers can keep going).
     */
    public Map<String, String> fetchActive(String workspaceId, String backendUrl, String addonToken) {
        Map<String, String> out = new LinkedHashMap<>();
        String path = "/v1/workspaces/" + workspaceId
                + "/users?status=ACTIVE&page=1&page-size=" + PAGE_SIZE;
        String raw;
        try {
            raw = api.get(workspaceId, backendUrl, addonToken, path, String.class);
        } catch (ClockifyApiException e) {
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
        for (JsonNode user : root) {
            String id = textOrNull(user, "id");
            if (id == null) continue;
            String name = textOrNull(user, "name");
            if (name == null) name = textOrNull(user, "email");
            if (name == null) continue;
            out.put(id, name);
        }
        return out;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }
}
