package me.apet97.breakcompliance.api;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.entities.AuditLog;
import me.apet97.breakcompliance.persistence.repositories.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class AuditControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuditLogRepository auditRepo;

    @Test
    void list_isAdminOnlyWorkspaceScopedDateBoundedAndDescending() throws Exception {
        seed("PRESET_APPLY", "2026-05-04T09:00:00Z", TestJwtForger.DEFAULT_WORKSPACE_ID);
        seed("FINDING_REVIEW", "2026-05-05T09:00:00Z", TestJwtForger.DEFAULT_WORKSPACE_ID);
        seed("OUT_OF_RANGE", "2026-04-30T09:00:00Z", TestJwtForger.DEFAULT_WORKSPACE_ID);
        seed("OTHER_WORKSPACE", "2026-05-05T10:00:00Z", "ws-other");

        mockMvc.perform(get("/api/audit")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .param("dateRangeStart", "2026-05-01")
                        .param("dateRangeEnd", "2026-05-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audit[*].action", contains("FINDING_REVIEW", "PRESET_APPLY")))
                .andExpect(jsonPath("$.audit[0].details.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.limit").value(200))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void list_capsLimit() throws Exception {
        seed("PRESET_APPLY", "2026-05-04T09:00:00Z", TestJwtForger.DEFAULT_WORKSPACE_ID);

        mockMvc.perform(get("/api/audit")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .param("dateRangeStart", "2026-05-01")
                        .param("dateRangeEnd", "2026-05-07")
                        .param("limit", "2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1000));
    }

    @Test
    void list_missingToken_returns401() throws Exception {
        mockMvc.perform(get("/api/audit")
                        .param("dateRangeStart", "2026-05-01")
                        .param("dateRangeEnd", "2026-05-07"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_nonAdmin_returns403() throws Exception {
        String memberToken = TestJwtForger.forge(Map.of("workspaceRole", "MEMBER"));
        mockMvc.perform(get("/api/audit")
                        .header("X-Addon-Token", memberToken)
                        .param("dateRangeStart", "2026-05-01")
                        .param("dateRangeEnd", "2026-05-07"))
                .andExpect(status().isForbidden());
    }

    private AuditLog seed(String action, String createdAt, String workspaceId) {
        AuditLog row = new AuditLog();
        row.setWorkspaceId(workspaceId);
        row.setActor("admin-user");
        row.setAction(action);
        row.setEntityType("Finding");
        row.setEntityId("finding-1");
        row.setDetails(Map.of("status", "ACKNOWLEDGED"));
        row.setCreatedAt(Instant.parse(createdAt));
        return auditRepo.saveAndFlush(row);
    }
}
