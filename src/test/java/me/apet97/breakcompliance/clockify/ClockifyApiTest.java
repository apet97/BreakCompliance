package me.apet97.breakcompliance.clockify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ClockifyApiTest {

    private static WireMockServer wireMock;
    private static String baseUrl;
    private ClockifyApi api;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMockAndApi() {
        WireMock.configureFor("localhost", wireMock.port());
        wireMock.resetAll();
        // Mirror the production bean wiring — short timeouts so retry tests
        // don't drag.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        RestClient client = RestClient.builder().requestFactory(factory).build();
        api = new ClockifyApi(client, new InMemoryRateLimiter(), new ObjectMapper());
    }

    @Test
    void getSendsXAddonTokenHeaderAndReturnsBody() {
        stubFor(get(urlEqualTo("/users/me"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"u-1\"}")
                        .withHeader("Content-Type", "application/json")));

        String body = api.get("ws-1", baseUrl, "secret-tok", "/users/me", String.class);

        assertThat(body).contains("\"id\":\"u-1\"");
        wireMock.verify(getRequestedFor(urlEqualTo("/users/me")).withHeader("X-Addon-Token", equalTo("secret-tok")));
    }

    @Test
    void retriesOn500ThenSucceeds() {
        stubFor(get(urlEqualTo("/once"))
                .inScenario("retry-500")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/once"))
                .inScenario("retry-500")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody("{}")
                        .withHeader("Content-Type", "application/json")));

        String body = api.get("ws-1", baseUrl, "tok", "/once", String.class);
        assertThat(body).isEqualTo("{}");
    }

    @Test
    void retriesOn429HonouringRetryAfter() {
        stubFor(get(urlEqualTo("/limited"))
                .inScenario("retry-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/limited"))
                .inScenario("retry-429")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")
                        .withHeader("Content-Type", "application/json")));

        long startMs = System.currentTimeMillis();
        String body = api.get("ws-1", baseUrl, "tok", "/limited", String.class);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(body).contains("\"ok\":true");
        assertThat(elapsedMs).isGreaterThanOrEqualTo(900);
    }

    @Test
    void clientError4xxNotRetried() {
        stubFor(get(urlEqualTo("/forbidden"))
                .willReturn(aResponse().withStatus(403).withBody("nope")));

        assertThatThrownBy(() -> api.get("ws-1", baseUrl, "tok", "/forbidden", String.class))
                .isInstanceOf(ClockifyApiException.class);
        wireMock.verify(1, getRequestedFor(urlEqualTo("/forbidden")));
    }

    @Test
    void httpScheme_nonLocalhost_rejected() {
        assertThatThrownBy(() -> api.get("ws-1", "http://evil.example.com", "tok", "/path", String.class))
                .isInstanceOf(ClockifyApiException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void nonClockifyHttpsHost_rejected() {
        assertThatThrownBy(() -> api.get("ws-1", "https://api.evil.com", "tok", "/path", String.class))
                .isInstanceOf(ClockifyApiException.class)
                .hasMessageContaining(".clockify.me");
    }

    @Test
    void retryAfter_httpDateForm_parsesCorrectly() {
        HttpHeaders headers = new HttpHeaders();
        Instant target = Instant.now().plus(Duration.ofSeconds(5));
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(target, ZoneOffset.UTC));
        headers.add(HttpHeaders.RETRY_AFTER, httpDate);

        Optional<Duration> parsed = ClockifyApi.parseRetryAfter(headers);

        assertThat(parsed).isPresent();
        // Allow some clock jitter between target computation and parse.
        assertThat(parsed.get().getSeconds()).isBetween(3L, 6L);
    }

    @Test
    void retryAfter_secondsForm_parsesCorrectly() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");

        Optional<Duration> parsed = ClockifyApi.parseRetryAfter(headers);

        assertThat(parsed).contains(Duration.ofSeconds(30));
    }

    @Test
    void retryAfter_malformed_returnsEmpty() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "not-a-number-or-date");

        assertThat(ClockifyApi.parseRetryAfter(headers)).isEmpty();
    }

    @Test
    void sustained429_throwsAfterMaxRetries() {
        // Always-429 — must abort after 4 retries instead of looping forever.
        stubFor(get(urlEqualTo("/always-limited"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));

        long startMs = System.currentTimeMillis();
        assertThatThrownBy(() -> api.get("ws-1", baseUrl, "tok", "/always-limited", String.class))
                .isInstanceOf(ClockifyApiException.class)
                .hasMessageContaining("429");
        long elapsedMs = System.currentTimeMillis() - startMs;
        // 4 retries × ~1s each ≈ 4s minimum (small jitter allowed).
        assertThat(elapsedMs).isGreaterThanOrEqualTo(3000);
    }

    /** Test double for the rate limiter — no Redis, never blocks. */
    private static class InMemoryRateLimiter extends ClockifyRateLimiter {
        InMemoryRateLimiter() {
            super(null, Integer.MAX_VALUE);
        }

        @Override
        public void acquire(String workspaceId) {
            // no-op for tests
        }
    }
}
