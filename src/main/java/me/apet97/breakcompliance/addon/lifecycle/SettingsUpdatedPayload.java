package me.apet97.breakcompliance.addon.lifecycle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * Parses inbound {@code POST /lifecycle/settings-updated} payloads into the
 * uniform {@code List<{id, value, ...}>} shape that
 * {@link InstallationService#handleSettingsUpdated} expects.
 *
 * <p>Clockify's canonical shape (documented in
 * {@code docs/clockify-marketplace/build/manifest/lifecycle.md:96-134}) wraps
 * the settings list in an object that also carries {@code workspaceId} and
 * {@code addonId}:
 *
 * <pre>{@code
 * {
 *   "workspaceId": "...",
 *   "addonId": "...",
 *   "settings": [
 *     {"id": "appliedPresetKey", "name": "...", "value": "germany-arbzg-style"},
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>Earlier dev-portal builds and our own {@code docs/api-calls.md} (now
 * corrected) had documented the payload as a bare array. The parser accepts
 * either to stay forward + backward compatible, plus a single
 * {@code {id, value}} object (defensive for a hypothetical single-field
 * delivery shape). Anything else collapses to an empty list — the caller
 * persists nothing rather than 400-looping a retry storm.
 */
public final class SettingsUpdatedPayload {

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP =
            new TypeReference<>() {};

    private SettingsUpdatedPayload() {
    }

    public static List<Map<String, Object>> extractUpdates(JsonNode payload, ObjectMapper mapper) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return List.of();
        }
        if (payload.isArray()) {
            return mapper.convertValue(payload, LIST_OF_MAPS);
        }
        if (payload.isObject()) {
            JsonNode settingsField = payload.get("settings");
            if (settingsField != null && settingsField.isArray()) {
                return mapper.convertValue(settingsField, LIST_OF_MAPS);
            }
            // Defensive: a single {id, value} object → wrap as a singleton list.
            if (payload.has("id") && payload.has("value")) {
                return List.of(mapper.convertValue(payload, MAP));
            }
        }
        return List.of();
    }

    /**
     * Materialises the object-wrapper (workspaceId, addonId, …, settings) as
     * a {@code Map} so the {@link PayloadDriftLogger} can WARN on unknown
     * top-level keys without re-parsing.
     */
    public static Map<String, Object> asObjectMap(JsonNode payload, ObjectMapper mapper) {
        if (payload == null || !payload.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(payload, MAP);
    }
}
