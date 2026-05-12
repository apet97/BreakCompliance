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
import me.apet97.breakcompliance.persistence.entities.FindingReview;
import me.apet97.breakcompliance.persistence.entities.ReviewStatus;
import me.apet97.breakcompliance.persistence.entities.Severity;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.FindingReviewRepository;
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

    @Autowired
    FindingRepository findingRepo;

    @Autowired
    FindingReviewRepository reviewRepo;

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
    void review_persistsAcknowledgedStatusAndNoteThenListSurfacesIt() throws Exception {
        Finding f = seedFinding("u-1", LocalDate.parse("2025-01-03"));
        String token = TestJwtForger.forgeInstalledToken();

        mockMvc.perform(post("/api/findings/" + f.getId() + "/review")
                        .header("X-Addon-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\",\"note\":\"hr ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingId").value(f.getId()))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.note").value("hr ok"));

        // Re-listing the same range must surface the review state inline so
        // the sidebar can render the chip without an extra round-trip.
        mockMvc.perform(get("/api/findings")
                        .header("X-Addon-Token", token)
                        .param("dateRangeStart", "2025-01-01")
                        .param("dateRangeEnd", "2025-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[0].review.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.findings[0].review.note").value("hr ok"));
    }

    @Test
    void review_overridesExistingRowAndClearsNoteOnOpen() throws Exception {
        Finding f = seedFinding("u-1", LocalDate.parse("2025-01-03"));
        String token = TestJwtForger.forgeInstalledToken();

        // First mark ACKNOWLEDGED with a note, then revert to OPEN — the
        // note should drop so audit text doesn't carry forward.
        mockMvc.perform(post("/api/findings/" + f.getId() + "/review")
                        .header("X-Addon-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\",\"note\":\"stale text\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/findings/" + f.getId() + "/review")
                        .header("X-Addon-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.note").isEmpty());
    }

    @Test
    void review_rejectsUnknownStatus() throws Exception {
        Finding f = seedFinding("u-1", LocalDate.parse("2025-01-03"));
        mockMvc.perform(post("/api/findings/" + f.getId() + "/review")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"WHATEVER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void review_returnsNotFoundForCrossWorkspaceFinding() throws Exception {
        Finding f = seedFindingFor("ws-other", "u-1", LocalDate.parse("2025-01-03"));
        mockMvc.perform(post("/api/findings/" + f.getId() + "/review")
                        .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isNotFound());
        // Make sure no row was created in the calling workspace either.
        org.assertj.core.api.Assertions.assertThat(
                reviewRepo.findById(new FindingReview.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, f.getId())))
                .isEmpty();
    }

    @Test
    void review_byNonAdmin_isForbidden() throws Exception {
        Finding f = seedFinding("u-1", LocalDate.parse("2025-01-03"));
        String memberToken = TestJwtForger.forge(Map.of("workspaceRole", "MEMBER"));
        mockMvc.perform(post("/api/findings/" + f.getId() + "/review")
                        .header("X-Addon-Token", memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isForbidden());
    }

    private Finding seedFinding(String userId, LocalDate date) {
        return seedFindingFor(TestJwtForger.DEFAULT_WORKSPACE_ID, userId, date);
    }

    private Finding seedFindingFor(String workspaceId, String userId, LocalDate date) {
        Finding f = new Finding();
        f.setWorkspaceId(workspaceId);
        f.setId(UUID.randomUUID().toString());
        f.setUserId(userId);
        f.setUserName("Alice");
        f.setDate(date);
        f.setTemplateId("custom-basic");
        f.setSeverity(Severity.VIOLATION);
        f.setCode(FindingCode.MISSING_REQUIRED_BREAK);
        f.setMessage("Worked 8h, no break recorded.");
        f.setEvidence(Map.of("workMinutes", 480, "breakMinutes", 0));
        f.setCreatedAt(Instant.parse("2025-01-04T09:00:00Z"));
        return findingRepo.saveAndFlush(f);
    }
}
