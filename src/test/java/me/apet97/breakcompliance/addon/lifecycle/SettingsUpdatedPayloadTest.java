package me.apet97.breakcompliance.addon.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks the SETTINGS_UPDATED payload parser's behaviour on every shape
 * Clockify might deliver. Pure-unit; no Spring context.
 */
class SettingsUpdatedPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalObjectShape_unwrapsSettingsArray() throws Exception {
        JsonNode payload = mapper.readTree("""
                {
                  "workspaceId": "ws-1",
                  "addonId": "addon-1",
                  "settings": [
                    {"id": "appliedPresetKey", "value": "germany-arbzg-style"},
                    {"id": "workThresholdMinutes", "value": 360}
                  ]
                }
                """);

        List<Map<String, Object>> updates = SettingsUpdatedPayload.extractUpdates(payload, mapper);

        assertThat(updates).hasSize(2);
        assertThat(updates.get(0)).containsEntry("id", "appliedPresetKey");
        assertThat(updates.get(1)).containsEntry("value", 360);
    }

    @Test
    void legacyArrayShape_isAcceptedAsIs() throws Exception {
        JsonNode payload = mapper.readTree("""
                [{"id":"workThresholdMinutes","value":275}]
                """);

        List<Map<String, Object>> updates = SettingsUpdatedPayload.extractUpdates(payload, mapper);

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).containsEntry("id", "workThresholdMinutes");
    }

    @Test
    void singleObjectShape_isWrappedAsSingleton() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"id":"appliedPresetKey","value":"california-style"}
                """);

        List<Map<String, Object>> updates = SettingsUpdatedPayload.extractUpdates(payload, mapper);

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).containsEntry("id", "appliedPresetKey");
        assertThat(updates.get(0)).containsEntry("value", "california-style");
    }

    @Test
    void emptyWrapper_returnsEmptyList() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"workspaceId":"ws-1","addonId":"addon-1","settings":[]}
                """);

        List<Map<String, Object>> updates = SettingsUpdatedPayload.extractUpdates(payload, mapper);

        assertThat(updates).isEmpty();
    }

    @Test
    void unknownObjectShape_returnsEmptyList() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"workspaceId":"ws-1","unexpected":"x"}
                """);

        List<Map<String, Object>> updates = SettingsUpdatedPayload.extractUpdates(payload, mapper);

        assertThat(updates).isEmpty();
    }

    @Test
    void nullPayload_returnsEmptyList() {
        assertThat(SettingsUpdatedPayload.extractUpdates(null, mapper)).isEmpty();
    }

    @Test
    void scalarPayload_returnsEmptyList() throws Exception {
        // "what if Clockify sends a string?" defensive case
        JsonNode payload = mapper.readTree("\"some string\"");
        assertThat(SettingsUpdatedPayload.extractUpdates(payload, mapper)).isEmpty();
    }

    @Test
    void asObjectMap_returnsContextForDriftLog() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"workspaceId":"ws-1","addonId":"addon-1","settings":[]}
                """);

        Map<String, Object> ctx = SettingsUpdatedPayload.asObjectMap(payload, mapper);

        assertThat(ctx).containsKey("workspaceId").containsKey("addonId").containsKey("settings");
    }

    @Test
    void asObjectMap_onArrayPayload_returnsEmpty() throws Exception {
        JsonNode payload = mapper.readTree("[]");
        assertThat(SettingsUpdatedPayload.asObjectMap(payload, mapper)).isEmpty();
    }
}
