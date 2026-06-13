package me.apet97.breakcompliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
class BreakComplianceApplicationTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MessageSource messageSource;

    @Test
    void contextLoads_andFlywayAppliedSchema() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE 'breakcompliance_%'",
                Integer.class);
        // 12 original + 2 added in V13 (workspace_holidays / workspace_time_off).
        assertThat(tableCount).isEqualTo(14);
    }

    @Test
    void contextLoadsFindingMessageBundle() {
        assertThat(messageSource.getMessage(
                "finding.missing_required_break", new Object[]{300, 240}, Locale.ENGLISH))
                .isEqualTo("Worked 300 minutes (threshold 240) with no qualifying break.");
        assertThat(messageSource.getMessage(
                "finding.insufficient_break_duration", new Object[]{15, 30}, Locale.ENGLISH))
                .isEqualTo("Qualifying break minutes 15 below required 30.");
        assertThat(messageSource.getMessage(
                "finding.max_continuous_work_exceeded", new Object[]{260, 240}, Locale.ENGLISH))
                .isEqualTo("Continuous work 260 minutes exceeds maximum 240.");
    }
}
