package me.apet97.breakcompliance.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} so the refresh-signal consumer and the
 * stuck-run reaper can run as periodic background jobs. Production
 * defaults the toggle on; integration tests set
 * {@code breakcompliance.scheduling.enabled=false} to keep scheduled
 * methods from firing during isolated transactional fixtures.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "breakcompliance.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
