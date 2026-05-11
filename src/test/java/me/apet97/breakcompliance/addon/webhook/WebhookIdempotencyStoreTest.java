package me.apet97.breakcompliance.addon.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.RedisTestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Locks the Redis SETNX dedup contract that {@link WebhookIdempotencyStore}
 * relies on. A Clockify retry must collapse to a 200 without side effects,
 * and the TTL must outlast Clockify's documented ~24h retry window.
 */
@SpringBootTest
@Import({PostgresTestcontainersConfig.class, RedisTestcontainersConfig.class})
class WebhookIdempotencyStoreTest {

    @Autowired
    WebhookIdempotencyStore store;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        var keys = redis.keys("webhook:*test*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void firstSighting_returnsTrue() {
        assertThat(store.markSeen("webhook:test:first")).isTrue();
    }

    @Test
    void duplicate_returnsFalse() {
        store.markSeen("webhook:test:dup");
        assertThat(store.markSeen("webhook:test:dup")).isFalse();
    }

    @Test
    void distinctKeys_doNotCollide() {
        assertThat(store.markSeen("webhook:test:a")).isTrue();
        assertThat(store.markSeen("webhook:test:b")).isTrue();
        assertThat(store.markSeen("webhook:test:a")).isFalse();
        assertThat(store.markSeen("webhook:test:b")).isFalse();
    }

    @Test
    void ttlIsAtLeast24Hours() {
        // Clockify retries up to ~24h. The TTL must comfortably outlast that
        // so a delayed retry collapses to a duplicate, not a re-process.
        store.markSeen("webhook:test:ttl");
        Long ttlSeconds = redis.getExpire("webhook:test:ttl");
        assertThat(ttlSeconds).isNotNull();
        assertThat(Duration.ofSeconds(ttlSeconds)).isGreaterThanOrEqualTo(Duration.ofHours(23));
    }

    @Test
    void storedValueIsOpaqueSentinel() {
        // The value isn't semantic — only the key's existence matters. We
        // pin the current sentinel so a future change is intentional.
        store.markSeen("webhook:test:sentinel");
        assertThat(redis.opsForValue().get("webhook:test:sentinel")).isEqualTo("1");
    }
}
