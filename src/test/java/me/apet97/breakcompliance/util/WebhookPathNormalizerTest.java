package me.apet97.breakcompliance.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookPathNormalizerTest {

    @Test
    void absoluteUrlExtractsPath() {
        assertThat(WebhookPathNormalizer.normalize("https://addon.example.com/webhook/new-time-entry"))
                .isEqualTo("/webhook/new-time-entry");
    }

    @Test
    void collapsesDoubledSlashes() {
        assertThat(WebhookPathNormalizer.normalize("//webhooks/timer-started"))
                .isEqualTo("/webhooks/timer-started");
        assertThat(WebhookPathNormalizer.normalize("https://addon.example.com//webhooks/x"))
                .isEqualTo("/webhooks/x");
    }

    @Test
    void addsLeadingSlash() {
        assertThat(WebhookPathNormalizer.normalize("webhook/new-time-entry"))
                .isEqualTo("/webhook/new-time-entry");
    }

    @Test
    void stripsTrailingSlash() {
        assertThat(WebhookPathNormalizer.normalize("/webhook/x/"))
                .isEqualTo("/webhook/x");
    }

    @Test
    void rootSlashRemains() {
        assertThat(WebhookPathNormalizer.normalize("/")).isEqualTo("/");
    }

    @Test
    void blankReturnsNull() {
        assertThat(WebhookPathNormalizer.normalize(null)).isNull();
        assertThat(WebhookPathNormalizer.normalize("")).isNull();
        assertThat(WebhookPathNormalizer.normalize("   ")).isNull();
    }

    @Test
    void idempotentOnAlreadyNormalized() {
        assertThat(WebhookPathNormalizer.normalize("/webhook/new-time-entry"))
                .isEqualTo("/webhook/new-time-entry");
    }
}
