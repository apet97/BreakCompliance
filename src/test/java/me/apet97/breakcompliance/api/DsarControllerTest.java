package me.apet97.breakcompliance.api;

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
class DsarControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuditLogRepository auditRepo;

    @Test
    void export_includesAuditLogsForActor() throws Exception {
        seed("PRESET_APPLY", "admin-user", TestJwtForger.DEFAULT_WORKSPACE_ID, "preset-1");
        seed("FINDING_REVIEW", "other-user", TestJwtForger.DEFAULT_WORKSPACE_ID, "finding-1");

        mockMvc.perform(get("/api/dsar/admin-user")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.auditLogs").value(1))
                .andExpect(jsonPath("$.auditLogs[0].actor").value("admin-user"))
                .andExpect(jsonPath("$.auditLogs[0].action").value("PRESET_APPLY"))
                .andExpect(jsonPath("$.auditLogs[0].entityId").value("preset-1"));
    }

    @Test
    void export_omitsAuditLogsFromOtherWorkspace() throws Exception {
        seed("PRESET_APPLY", "admin-user", TestJwtForger.DEFAULT_WORKSPACE_ID, "preset-1");
        seed("OTHER_WORKSPACE", "admin-user", "ws-other", "preset-2");

        mockMvc.perform(get("/api/dsar/admin-user")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.auditLogs").value(1))
                .andExpect(jsonPath("$.auditLogs[0].action").value("PRESET_APPLY"));
    }

    @Test
    void export_nonAdminReturns403() throws Exception {
        String memberToken = TestJwtForger.forge(Map.of("workspaceRole", "MEMBER"));

        mockMvc.perform(get("/api/dsar/admin-user")
                        .header("X-Addon-Token", memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void export_missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/dsar/admin-user"))
                .andExpect(status().isUnauthorized());
    }

    private AuditLog seed(String action, String actor, String workspaceId, String entityId) {
        AuditLog row = new AuditLog();
        row.setWorkspaceId(workspaceId);
        row.setActor(actor);
        row.setAction(action);
        row.setEntityType("Finding");
        row.setEntityId(entityId);
        row.setDetails(Map.of("status", "ACKNOWLEDGED"));
        row.setCreatedAt(Instant.parse("2026-05-04T09:00:00Z"));
        return auditRepo.saveAndFlush(row);
    }
}
