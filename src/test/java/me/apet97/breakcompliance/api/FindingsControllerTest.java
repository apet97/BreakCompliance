package me.apet97.breakcompliance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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
}
