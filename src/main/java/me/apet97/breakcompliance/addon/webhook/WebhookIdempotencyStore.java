package me.apet97.breakcompliance.addon.webhook;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Atomic "have I seen this webhook delivery before?" check backed by Redis
 * {@code SETNX} (via {@code setIfAbsent}). TTL must outlast Clockify's retry
 * window — the {@code webhook-reliability} guide says up to 24h, so we pin
 * at 24h. A return value of {@code false} means the key already existed and
 * the caller should short-circuit to 200 without side effects.
 */
@Component
public class WebhookIdempotencyStore {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String SENTINEL = "1";

    private final StringRedisTemplate redis;

    public WebhookIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns true if this is the first time we've seen {@code key}; false if duplicate. */
    public boolean markSeen(String key) {
        Boolean result = redis.opsForValue().setIfAbsent(key, SENTINEL, TTL);
        return Boolean.TRUE.equals(result);
    }
}
