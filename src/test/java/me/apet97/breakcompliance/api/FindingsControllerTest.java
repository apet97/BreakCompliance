package me.apet97.breakcompliance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the findings endpoints' response contracts that sidebar.js consumes:
 * <ul>
 *   <li>{@code POST /api/findings/evaluate} returns {@code findingsCreated},
 *       not {@code count} — sidebar uses {@code n.findingsCreated} in the
 *       diagnostics panel.
 *   <li>{@code GET /api/findings} wraps the list as {@code {findings: [...]}}
 *       so {@code r.findings} destructures correctly.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class FindingsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FindingRepository findingRepo;

    @Test
    void evaluate_emitsFindingsCreatedKey() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(post("/api/findings/evaluate")
                        .header("X-Addon-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateRangeStart\":\"2025-01-01\",\"dateRangeEnd\":\"2025-01-07\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingsCreated").isNumber())
                .andExpect(jsonPath("$.count").doesNotExist())
                .andExpect(jsonPath("$.dateRangeStart").value("2025-01-01"))
                .andExpect(jsonPath("$.dateRangeEnd").value("2025-01-07"));
    }

    @Test
    void list_wrapsResponseInFindingsField() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/findings")
                        .header("X-Addon-Token", token)
                        .param("dateRangeStart", "2025-01-01")
                        .param("dateRangeEnd", "2025-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings").isArray());
    }

    @Test
    void list_missingToken_returns401() throws Exception {
        mockMvc.perform(get("/api/findings")
                        .param("dateRangeStart", "2025-01-01")
                        .param("dateRangeEnd", "2025-01-07"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void export_returnsCsvAttachmentWithHeader() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        MvcResult result = mockMvc.perform(get("/api/findings/export")
                        .header("X-Addon-Token", token)
                        .param("dateRangeStart", "2025-01-01")
                        .param("dateRangeEnd", "2025-01-07"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.startsWith("attachment; filename=\"break-compliance-")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith("text/csv"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .startsWith("date,userId,userName,severity,code,message,workMinutes,breakMinutes,syntheticBreakMinutes,templateId,createdAt\r\n");
    }

    @Test
    void export_quotesCellsContainingCommasAndQuotes() throws Exception {
        // Seed a finding whose message has a comma, an embedded quote, and a
        // newline so the escaping path is exercised end-to-end.
        Finding f = new Finding();
        f.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
        f.setId(UUID.randomUUID().toString());
        f.setUserId("u-1");
        f.setUserName("Alice O'Brien, CPA");
        f.setDate(LocalDate.parse("2025-01-03"));
        f.setTemplateId("custom-basic");
        f.setSeverity(Severity.VIOLATION);
        f.setCode(FindingCode.MISSING_REQUIRED_BREAK);
        f.setMessage("Worked 8h \"straight\", no break recorded\nfor 2025-01-03.");
        f.setEvidence(Map.of(
                "workMinutes", 480,
                "breakMinutes", 0,
                "syntheticBreakMinutes", 0));
        f.setCreatedAt(Instant.parse("2025-01-04T09:00:00Z"));
        findingRepo.saveAndFlush(f);

        String token = TestJwtForger.forgeInstalledToken();
        MvcResult result = mockMvc.perform(get("/api/findings/export")
                        .header("X-Addon-Token", token)
                        .param("dateRangeStart", "2025-01-01")
                        .param("dateRangeEnd", "2025-01-07"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // userName cell: contains a comma and a quote, must be wrapped and
        // the inner quote doubled per RFC 4180.
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"Alice O'Brien, CPA\"");
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"Worked 8h \"\"straight\"\", no break recorded\nfor 2025-01-03.\"");
        // workMinutes=480 emits as a plain number, breakMinutes=0 collapses
        // to an empty cell so spreadsheets stay readable.
        org.assertj.core.api.Assertions.assertThat(body).contains(",480,,,custom-basic,");
    }

    @Test
    void export_rejectsUnknownFormat() throws Exception {
        mockMvc.perform(get("/api/findings/export")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .param("dateRangeStart", "2025-01-01")
                        .param("dateRangeEnd", "2025-01-07")
                        .param("format", "xlsx"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_missingToken_returns401() throws Exception {
        mockMvc.perform(get("/api/findings/export")
                        .param("dateRangeStart", "2025-01-01")
                        .param("dateRangeEnd", "2025-01-07"))
                .andExpect(status().isUnauthorized());
    }
}
