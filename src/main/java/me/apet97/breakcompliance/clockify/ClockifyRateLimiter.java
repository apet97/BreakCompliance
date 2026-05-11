package me.apet97.breakcompliance.clockify;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-workspace rate limiter targeting Clockify's add-on rate limit of
 * 50 requests/sec/workspace. Implemented as a Redis fixed-window counter
 * keyed by {@code rl:{workspaceId}:{epochSecond}} — a request increments
 * the counter, the first increment sets a 2s TTL so old buckets self-expire.
 * If the counter exceeds the budget the caller is blocked until the next
 * one-second window starts, ensuring we never exceed Clockify's quota.
 *
 * <p>Fixed-window is intentionally simple: token-bucket gives a smoother
 * profile but the 50/s budget is generous and bursty workloads (ingestion
 * runs) are bounded by pagination size anyway. Sliding-window can be a
 * follow-up if observability suggests it's needed.
 */
@Component
public class ClockifyRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ClockifyRateLimiter.class);
    private static final int DEFAULT_BUDGET_PER_SECOND = 50;
    private static final long MAX_SLEEP_MS = 1100L;

    private final StringRedisTemplate redis;
    private final int budgetPerSecond;

    public ClockifyRateLimiter(
            StringRedisTemplate redis,
            @Value("${breakcompliance.clockify.rate-limit-per-second:50}") int budgetPerSecond) {
        this.redis = redis;
        this.budgetPerSecond = budgetPerSecond > 0 ? budgetPerSecond : DEFAULT_BUDGET_PER_SECOND;
    }

    public void acquire(String workspaceId) {
        long now = System.currentTimeMillis();
        long second = now / 1000L;
        String key = "rl:" + workspaceId + ":" + second;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(2));
        }
        if (count != null && count > budgetPerSecond) {
            long sleepMs = Math.min(MAX_SLEEP_MS, 1000L - (now % 1000L) + 5L);
            log.debug("clockify-rate-limit.wait workspace={} count={} sleep_ms={}", workspaceId, count, sleepMs);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ClockifyApiException("rate-limit wait interrupted", 0, e);
            }
            // Reset attempt at the new bucket so we don't recurse indefinitely
            long secondAfter = System.currentTimeMillis() / 1000L;
            if (secondAfter != second) {
                acquire(workspaceId);
            }
        }
    }
}
