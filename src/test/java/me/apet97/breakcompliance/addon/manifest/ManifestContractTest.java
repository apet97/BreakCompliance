package me.apet97.breakcompliance.addon.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Locks the manifest contract: key, name, plan, scopes, and the absence of
 * any {@code _WRITE} scope are pinned so a regression is caught at build
 * time before a misconfigured manifest reaches the marketplace.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
class ManifestContractTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void manifest_endpointReturnsJson() throws Exception {
        mockMvc.perform(get("/manifest"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void manifest_topLevelFieldsMatchTs() throws Exception {
        mockMvc.perform(get("/manifest"))
                .andExpect(jsonPath("$.key").value("break-compliance-jvm"))
                .andExpect(jsonPath("$.name").value("Break Compliance"))
                .andExpect(jsonPath("$.minimalSubscriptionPlan").value("BASIC"))
                .andExpect(jsonPath("$.description").exists())
                .andExpect(jsonPath("$.baseUrl").exists());
    }

    @Test
    void manifest_scopesAreExactlyLeastRequired() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode scopes = root.get("scopes");

        assertThat(scopes).isNotNull();
        assertThat(scopes.isArray()).isTrue();

        List<String> scopeNames = scopes.findValuesAsText("").stream()
                .filter(s -> !s.isBlank())
                .toList();
        if (scopeNames.isEmpty()) {
            scopeNames = scopes.findValues("$").stream()
                    .map(JsonNode::asText)
                    .toList();
        }
        // The scopes may serialize as bare strings or as objects per the
        // generated v1_3 model. Pull the leaf strings either way.
        List<String> normalized = new java.util.ArrayList<>();
        scopes.forEach(node -> normalized.add(node.isObject() ? node.get("name").asText() : node.asText()));

        assertThat(normalized).containsExactlyInAnyOrder(
                "TIME_ENTRY_READ", "USER_READ", "REPORTS_READ", "WORKSPACE_READ");
    }

    @Test
    void manifest_doesNotRequestWriteScopes() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain("_WRITE");
    }

    @Test
    void manifest_declaresIconPathAndStructuredSettings() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(root.get("iconPath").asText()).isEqualTo("/icon.svg");

        JsonNode settings = root.get("settings");
        assertThat(settings).isNotNull();
        assertThat(settings.isObject()).isTrue();

        JsonNode tabs = settings.get("tabs");
        assertThat(tabs.isArray()).isTrue();
        assertThat(tabs).hasSize(1);

        JsonNode general = tabs.get(0);
        assertThat(general.get("id").asText()).isEqualTo("general");
        assertThat(general.get("name").asText()).isEqualTo("General");

        JsonNode generalSettings = general.get("settings");
        assertThat(generalSettings.isArray()).isTrue();
        List<String> settingIds = new java.util.ArrayList<>();
        generalSettings.forEach(node -> settingIds.add(node.get("id").asText()));
        assertThat(settingIds).containsExactly(
                "defaultTemplateId", "timezoneStrategy", "fallbackDetectionEnabled");
    }

    @Test
    void defaultTemplateId_allowedValuesMatchBuiltInPresetKeys() throws Exception {
        // The native Clockify structured-settings page renders the dropdown
        // off the manifest. If allowedValues lists IDs that don't correspond
        // to seeded RuleTemplate rows, admins can pick a ghost ID. Pin them
        // to the actual preset keys defined in RuleTemplatePresets.
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode generalSettings = root.get("settings").get("tabs").get(0).get("settings");
        JsonNode defaultTemplate = null;
        for (JsonNode node : generalSettings) {
            if ("defaultTemplateId".equals(node.get("id").asText())) {
                defaultTemplate = node;
                break;
            }
        }
        assertThat(defaultTemplate).isNotNull();

        List<String> allowedValues = new java.util.ArrayList<>();
        defaultTemplate.get("allowedValues").forEach(v -> allowedValues.add(v.asText()));
        assertThat(allowedValues).containsExactlyInAnyOrder(
                "germany-arbg-style", "california-style", "custom-basic");

        assertThat(defaultTemplate.get("value").asText()).isEqualTo("germany-arbg-style");
    }
}
