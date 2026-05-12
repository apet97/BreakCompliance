package me.apet97.breakcompliance.addon.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookIdempotencyStore idempotency;
    private final RefreshSignalService signals;
    private final ObjectMapper objectMapper;

    public WebhookController(
            WebhookIdempotencyStore idempotency,
            RefreshSignalService signals,
            ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.signals = signals;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = {"/new-time-entry", "/time-entry-updated", "/time-entry-deleted"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> timeEntryWebhook(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        NormalizedClaims claims = (NormalizedClaims) request.getAttribute(RequestAttributes.NORMALIZED_CLAIMS);
        String eventType = (String) request.getAttribute("breakcompliance.webhook-event-type");
        if (claims == null || eventType == null) {
            throw new IllegalStateException("webhook auth filter did not populate request attributes");
        }
        byte[] rawBody = body == null ? new byte[0] : body;
        String idempotencyKey = WebhookEventId.compute(claims.workspaceId(), eventType, rawBody);
        if (!idempotency.markSeen(idempotencyKey)) {
            log.debug("webhook.duplicate workspace={} event={}", claims.workspaceId(), eventType);
            return ResponseEntity.noContent().build();
        }
        String dateHint = extractDateHint(rawBody);
        signals.recordWebhookSignal(claims.workspaceId(), eventType, dateHint);
        return ResponseEntity.noContent().build();
    }

    /**
     * Best-effort extraction of the affected calendar date from a Clockify
     * time-entry webhook payload. Reads {@code timeInterval.start}, parses
     * it as an ISO-8601 offset datetime, and returns the corresponding
     * UTC calendar date in {@code yyyy-MM-dd} form. Any malformed,
     * missing, or unparseable payload yields {@code null} — the signal is
     * still recorded; the refresh-signal consumer falls back to its
     * default re-ingest window when the hint is absent.
     */
    private String extractDateHint(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ignored) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode start = root.path("timeInterval").path("start");
        if (start.isMissingNode() || !start.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(start.asText())
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString();
        } catch (DateTimeParseException ignored) {
            // Some sandboxes ship `start` as a plain date — accept that too.
            try {
                return LocalDate.parse(start.asText()).toString();
            } catch (DateTimeParseException stillBad) {
                return null;
            }
        }
    }
}
