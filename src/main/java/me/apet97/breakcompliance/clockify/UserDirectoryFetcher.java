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
 * <p>Walks 200-row pages so large workspaces still reconcile display names.
 */
@Component
public class UserDirectoryFetcher {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 500;

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
        int page = 1;
        while (page <= MAX_PAGES) {
            String path = "/v1/workspaces/" + workspaceId
                    + "/users?status=ACTIVE&page=" + page + "&page-size=" + PAGE_SIZE;
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
            if (root.isEmpty() || root.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return out;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }
}
