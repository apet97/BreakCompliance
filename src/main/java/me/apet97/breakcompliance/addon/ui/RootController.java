package me.apet97.breakcompliance.addon.ui;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiny 200 OK on {@code GET /} so a generic root probe (e.g., from a
 * marketplace validator's "is this host alive" check) gets a healthy response
 * instead of 404. The real endpoints — {@code /manifest}, {@code /healthz},
 * {@code /sidebar}, lifecycle, webhooks — are documented in
 * {@link me.apet97.breakcompliance.config.ClockifyAddonConfig} and the
 * manifest itself.
 */
@RestController
public class RootController {

    @GetMapping(value = "/", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> root() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("break-compliance addon — see /manifest");
    }
}
