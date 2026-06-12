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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

        // Marketplace least-scope: workspace metadata is not read anywhere
        // in the codebase, so WORKSPACE_READ was dropped to keep the consent
        // dialog honest. Detailed-report calls need REPORTS_READ only.
        assertThat(normalized).containsExactlyInAnyOrder(
                "TIME_ENTRY_READ", "USER_READ", "REPORTS_READ");
    }

    @Test
    void manifest_doesNotRequestWriteScopes() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain("_WRITE");
    }

    @Test
    void manifest_sidebarComponentIsAdminOnly() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode sidebar = null;
        for (JsonNode component : root.get("components")) {
            if ("sidebar".equalsIgnoreCase(component.get("type").asText())) {
                sidebar = component;
                break;
            }
        }

        assertThat(sidebar).isNotNull();
        assertThat(sidebar.get("path").asText()).isEqualTo("/sidebar");
        assertThat(sidebar.get("accessLevel").asText()).isEqualTo("ADMINS");
    }

    @Test
    void manifest_declaresIconPathAndSingleSettingsTab() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(root.get("iconPath").asText()).isEqualTo("/icon.svg");

        JsonNode settings = root.get("settings");
        assertThat(settings).isNotNull();
        assertThat(settings.isObject()).isTrue();

        JsonNode tabs = settings.get("tabs");
        assertThat(tabs.isArray()).isTrue();
        // §18: single tab. General tab and Custom Policy tab were merged into
        // one "Break Compliance" tab so admins see one editing surface.
        assertThat(tabs).hasSize(1);

        JsonNode tab = tabs.get(0);
        assertThat(tab.get("id").asText()).isEqualTo("breakCompliance");
        assertThat(tab.get("name").asText()).isEqualTo("Break Compliance");
    }

    @Test
    void manifest_singleTab_listsTenFieldsInOrder() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode tabSettings = root.get("settings").get("tabs").get(0).get("settings");
        assertThat(tabSettings.isArray()).isTrue();

        List<String> ids = new java.util.ArrayList<>();
        tabSettings.forEach(node -> ids.add(node.get("id").asText()));
        // `appliedPresetKey` was moved to the sidebar (POST /api/presets/apply)
        // because Clockify's per-field rendering breaks any cross-field loader.
        // Original 10 thresholds stay first in evaluation order, followed by
        // the operational / engine-tuning settings added in later waves.
        assertThat(ids).containsExactly(
                "workThresholdMinutes",
                "breakThresholdMinutes",
                "minBreakSegmentMinutes",
                "maxContinuousWorkMinutes",
                "gracePeriodMinutes",
                "allowSplitBreaks",
                "secondWorkThresholdMinutes",
                "secondBreakThresholdMinutes",
                "timezoneStrategy",
                "fallbackDetectionEnabled",
                // P6.2 / P3.3
                "exemptUserIds",
                "refreshDebounceSeconds",
                // P1.3 / P2.9
                "excludeUnsubmittedEntries",
                "severityOverrideMissingBreak",
                "severityOverrideInsufficientBreak",
                "severityOverrideMaxContinuous",
                // P1.4
                "nightShiftAttribution");
    }

    @Test
    void timezoneStrategy_isRequiredAndSingleValue() throws Exception {
        MvcResult result = mockMvc.perform(get("/manifest")).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode tabSettings = root.get("settings").get("tabs").get(0).get("settings");
        JsonNode timezoneStrategy = null;
        for (JsonNode node : tabSettings) {
            if ("timezoneStrategy".equals(node.get("id").asText())) {
                timezoneStrategy = node;
                break;
            }
        }
        assertThat(timezoneStrategy).isNotNull();
        List<String> tzAllowed = new java.util.ArrayList<>();
        timezoneStrategy.get("allowedValues").forEach(v -> tzAllowed.add(v.asText()));
        // P1.6 added UTC alongside the entry-local default.
        assertThat(tzAllowed).containsExactly(
                "Use entry's local time zone",
                "Use UTC for every entry");
        assertThat(timezoneStrategy.get("value").asText()).isEqualTo("Use entry's local time zone");
        // required:true suppresses Clockify's auto-injected "None" option.
        assertThat(timezoneStrategy.get("required").asBoolean()).isTrue();
    }
}
