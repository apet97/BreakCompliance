package me.apet97.breakcompliance.addon.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic idempotency key for an inbound webhook delivery. The key is
 * derived from the event type and the SHA-256 of the raw body so a retry of
 * the same event from Clockify produces the same Redis key and short-circuits
 * to a 200 without side effects.
 */
public final class WebhookEventId {

    private WebhookEventId() {
    }

    public static String compute(String eventType, byte[] body) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(eventType.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(body);
            return "webhook:" + eventType + ":" + HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
