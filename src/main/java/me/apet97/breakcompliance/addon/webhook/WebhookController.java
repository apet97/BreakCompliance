package me.apet97.breakcompliance.addon.webhook;

import jakarta.servlet.http.HttpServletRequest;
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

    public WebhookController(WebhookIdempotencyStore idempotency, RefreshSignalService signals) {
        this.idempotency = idempotency;
        this.signals = signals;
    }

    @PostMapping(value = "/new-time-entry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> newTimeEntry(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        NormalizedClaims claims = (NormalizedClaims) request.getAttribute(RequestAttributes.NORMALIZED_CLAIMS);
        String eventType = (String) request.getAttribute("breakcompliance.webhook-event-type");
        if (claims == null || eventType == null) {
            throw new IllegalStateException("webhook auth filter did not populate request attributes");
        }
        byte[] rawBody = body == null ? new byte[0] : body;
        String idempotencyKey = WebhookEventId.compute(eventType, rawBody);
        if (!idempotency.markSeen(idempotencyKey)) {
            log.debug("webhook.duplicate workspace={} event={}", claims.workspaceId(), eventType);
            return ResponseEntity.noContent().build();
        }
        signals.recordWebhookSignal(claims.workspaceId(), eventType);
        return ResponseEntity.noContent().build();
    }
}
