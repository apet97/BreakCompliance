package me.apet97.breakcompliance.clockify;

import static org.assertj.core.api.Assertions.assertThat;

import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.RedisTestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Per-workspace fixed-window rate limiter. The production budget is 50
 * req/sec; these tests run with a smaller budget so the over-budget branch
 * is reachable without burning whole seconds.
 */
@SpringBootTest(properties = "breakcompliance.clockify.rate-limit-per-second=3")
@Import({PostgresTestcontainersConfig.class, RedisTestcontainersConfig.class})
class ClockifyRateLimiterTest {

    @Autowired
    ClockifyRateLimiter limiter;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        var keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void withinBudget_acquiresImmediately() {
        long start = System.nanoTime();
        // Budget is 3/sec under @SpringBootTest properties.
        for (int i = 0; i < 3; i++) {
            limiter.acquire("ws-ratelim-fast");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // No sleep should fire while under budget — bound generously to keep
        // the test robust on slow CI.
        assertThat(elapsedMs).isLessThan(500L);
    }

    @Test
    void overBudget_sleepsUntilNextSecondBucket() {
        // Align to a fresh bucket so all three "under budget" acquires land
        // in the same second — otherwise the 3rd can roll into a new bucket
        // and the 4th's sleep is only the leftover of that bucket (we've
        // seen sub-50ms sleeps on slow CI runners). Sleep into the next
        // millisecond-rounded second start, then begin.
        long nowMs = System.currentTimeMillis();
        long nextSecondStartMs = ((nowMs / 1000L) + 1L) * 1000L;
        try {
            Thread.sleep(nextSecondStartMs - nowMs + 5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4th acquire on a budget of 3 must sleep. With the bucket-aligned
        // start the sleep is close to a full second; ~2s ceiling absorbs
        // any CI scheduler jitter.
        for (int i = 0; i < 3; i++) {
            limiter.acquire("ws-ratelim-burst");
        }
        long start = System.nanoTime();
        limiter.acquire("ws-ratelim-burst");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(2000L);
        // It must have actually slept some non-trivial amount — anything
        // below 250ms means the over-budget branch was skipped or the
        // bucket boundary landed in the middle of the 3-acquire prelude
        // (which the bucket-alignment above prevents).
        assertThat(elapsedMs).isGreaterThan(250L);
    }

    @Test
    void perWorkspaceIsolation_oneWorkspaceBurstDoesNotStarveAnother() {
        // Burn ws-A's budget then ws-B should still acquire immediately.
        for (int i = 0; i < 3; i++) {
            limiter.acquire("ws-ratelim-A");
        }
        long start = System.nanoTime();
        limiter.acquire("ws-ratelim-B");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(200L);
    }
}
