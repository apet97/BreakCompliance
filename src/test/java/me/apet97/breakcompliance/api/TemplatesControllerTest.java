package me.apet97.breakcompliance.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.addon.auth.TestJwtForger;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the {@code GET /api/templates} response contract that the sidebar JS
 * destructures as {@code {templates: [...]}}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@Transactional
class TemplatesControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RuleTemplateRepository templates;

    @BeforeEach
    void cleanState() {
        templates.deleteAll();
    }

    @Test
    void list_wrapsResponseInTemplatesField_seedsBuiltInsLazily() throws Exception {
        String token = TestJwtForger.forgeInstalledToken();
        mockMvc.perform(get("/api/templates").header("X-Addon-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates").isArray())
                .andExpect(jsonPath("$.templates", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.templates[*].presetKey",
                        Matchers.containsInAnyOrder("custom-basic", "germany-arbzg-style", "california-style")));
    }

    @Test
    void list_missingToken_returns401() throws Exception {
        mockMvc.perform(get("/api/templates"))
                .andExpect(status().isUnauthorized());
    }
}
