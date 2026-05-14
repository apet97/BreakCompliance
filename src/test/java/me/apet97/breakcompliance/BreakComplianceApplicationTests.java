package me.apet97.breakcompliance;

import static org.assertj.core.api.Assertions.assertThat;

import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
class BreakComplianceApplicationTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads_andFlywayAppliedSchema() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE 'breakcompliance_%'",
                Integer.class);
        // 12 original + 2 added in V13 (workspace_holidays / workspace_time_off).
        assertThat(tableCount).isEqualTo(14);
    }
}
