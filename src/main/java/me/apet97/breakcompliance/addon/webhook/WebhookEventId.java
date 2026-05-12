package me.apet97.breakcompliance.addon.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic idempotency key for an inbound webhook delivery. The key is
 * derived from (workspaceId, eventType, SHA-256 of the raw body) so a retry
 * of the same event from Clockify produces the same Redis key and short-
 * circuits to a 200 without side effects. workspaceId is taken from the
 * signed JWT, not the body, and is part of both the digest input and the
 * key prefix — defense-in-depth so two workspaces can never share an
 * idempotency slot even if Clockify ever shipped byte-identical bodies.
 */
public final class WebhookEventId {

    private WebhookEventId() {
    }

    public static String compute(String workspaceId, String eventType, byte[] body) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(workspaceId.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(eventType.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(body);
            return "webhook:" + workspaceId + ":" + eventType + ":"
                    + HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
