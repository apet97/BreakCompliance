package me.apet97.breakcompliance.clockify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around Spring's {@link RestClient} that enforces Clockify's
 * marketplace API contract:
 *
 * <ul>
 *   <li>Authentication via {@code X-Addon-Token} header (not
 *       {@code Authorization}).
 *   <li>SSRF guard: every backendUrl/reportsUrl must be HTTPS and point at a
 *       {@code *.clockify.me} host (with {@code http://localhost} permitted
 *       for tests against WireMock).
 *   <li>429 handling: parse {@code Retry-After} (seconds or HTTP date), sleep
 *       up to 30s, retry once. The per-workspace rate limiter usually
 *       prevents this but Clockify's quota is shared across all calls so a
 *       cold-start burst can still hit it.
 *   <li>5xx handling: exponential backoff (1s → 2s → 4s, cap 30s), max 4
 *       attempts. Persistent failures throw {@link ClockifyApiException}.
 * </ul>
 */
@Component
public class ClockifyApi {

    private static final Logger log = LoggerFactory.getLogger(ClockifyApi.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final int MAX_5XX_RETRIES = 4;
    private static final int MAX_429_RETRIES = 4;

    private final RestClient restClient;
    private final ClockifyRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ClockifyApi(RestClient clockifyRestClient, ClockifyRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.restClient = clockifyRestClient;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    public <T> T get(String workspaceId, String baseUrl, String addonToken, String path, Class<T> type) {
        validateBaseUrl(baseUrl);
        String url = baseUrl + path;
        return executeWithRetry(workspaceId, attempt -> restClient
                .get()
                .uri(URI.create(url))
                .header("X-Addon-Token", addonToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(type));
    }

    public <T> T post(String workspaceId, String baseUrl, String addonToken, String path, Object body, Class<T> type) {
        validateBaseUrl(baseUrl);
        String url = baseUrl + path;
        return executeWithRetry(workspaceId, attempt -> restClient
                .post()
                .uri(URI.create(url))
                .header("X-Addon-Token", addonToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(type));
    }

    public <T> org.springframework.http.ResponseEntity<T> postWithHeaders(String workspaceId, String baseUrl, String addonToken, String path, Object body, Class<T> type) {
        validateBaseUrl(baseUrl);
        String url = baseUrl + path;
        return executeWithRetry(workspaceId, attempt -> restClient
                .post()
                .uri(URI.create(url))
                .header("X-Addon-Token", addonToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(type));
    }

    /** Convenience for endpoints that return a raw JSON list of objects. */
    public List<Map<String, Object>> postReturningList(
            String workspaceId, String baseUrl, String addonToken, String path, Object body) {
        String raw = post(workspaceId, baseUrl, addonToken, path, body, String.class);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ClockifyApiException("failed to parse Clockify response", 0, e);
        }
    }

    private <T> T executeWithRetry(String workspaceId, Attempt<T> attempt) {
        Duration backoff = Duration.ofSeconds(1);
        int retries = 0;
        int rateLimitRetries = 0;
        while (true) {
            rateLimiter.acquire(workspaceId);
            try {
                return attempt.invoke(retries);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (rateLimitRetries >= MAX_429_RETRIES) {
                    throw new ClockifyApiException(
                            "Clockify 429 after " + MAX_429_RETRIES + " retries",
                            e.getStatusCode().value(), e);
                }
                Duration retryAfter = parseRetryAfter(e.getResponseHeaders())
                        .map(ClockifyApi::cap)
                        .orElse(Duration.ofSeconds(1));
                log.info("clockify.429 workspace={} retry_after={} attempt={}",
                        workspaceId, retryAfter, rateLimitRetries + 1);
                sleep(retryAfter.toMillis());
                rateLimitRetries++;
            } catch (HttpServerErrorException e) {
                if (retries >= MAX_5XX_RETRIES) {
                    throw new ClockifyApiException(
                            "Clockify 5xx after " + MAX_5XX_RETRIES + " retries", e.getStatusCode().value(), e);
                }
                log.info(
                        "clockify.5xx workspace={} status={} retry={} backoff={}",
                        workspaceId,
                        e.getStatusCode(),
                        retries + 1,
                        backoff);
                sleep(backoff.toMillis());
                backoff = doubleCapped(backoff);
                retries++;
            } catch (HttpClientErrorException e) {
                throw new ClockifyApiException(
                        "Clockify client error: " + e.getStatusCode(), e.getStatusCode().value(), e);
            } catch (ResourceAccessException e) {
                if (retries >= MAX_5XX_RETRIES) {
                    throw new ClockifyApiException("Clockify network error", 0, e);
                }
                log.info("clockify.network workspace={} retry={} backoff={}", workspaceId, retries + 1, backoff);
                sleep(backoff.toMillis());
                backoff = doubleCapped(backoff);
                retries++;
            }
        }
    }

    private static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ClockifyApiException("baseUrl required", 0);
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new ClockifyApiException("invalid baseUrl: " + baseUrl, 0, e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new ClockifyApiException("baseUrl missing scheme/host: " + baseUrl, 0);
        }
        boolean localhostForTests = "localhost".equals(host) || "127.0.0.1".equals(host);
        if (!"https".equals(scheme) && !localhostForTests) {
            throw new ClockifyApiException("baseUrl must be HTTPS: " + baseUrl, 0);
        }
        if (!localhostForTests && !host.endsWith(".clockify.me")) {
            throw new ClockifyApiException("baseUrl host must end in .clockify.me: " + host, 0);
        }
    }

    /**
     * Parse {@code Retry-After} per RFC 7231 §7.1.3 — either a non-negative
     * delta in seconds OR an HTTP-date. Returns {@link Optional#empty()} for
     * malformed values so the caller falls back to its own backoff. The
     * caller is expected to {@link #cap(Duration)} the value at
     * {@link #MAX_BACKOFF} so a hostile or buggy server can't pin our
     * worker thread for hours.
     */
    static Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        String raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        // delta-seconds form (most common): e.g. "30"
        try {
            long seconds = Long.parseLong(value);
            return Optional.of(Duration.ofSeconds(Math.max(1, seconds)));
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date
        }
        // HTTP-date form: e.g. "Wed, 21 Oct 2015 07:28:00 GMT"
        try {
            Instant target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delta = Duration.between(Instant.now(), target);
            return Optional.of(delta.isNegative() || delta.isZero() ? Duration.ofSeconds(1) : delta);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Duration cap(Duration d) {
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static Duration doubleCapped(Duration d) {
        Duration next = d.multipliedBy(2);
        return next.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : next;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClockifyApiException("retry sleep interrupted", 0, e);
        }
    }

    /** Used for static analysis to avoid creating a lambda type for every retry attempt. */
    @FunctionalInterface
    private interface Attempt<T> {
        T invoke(int attempt);
    }

    /** Static convenience for callers that only need HTTP-status classification. */
    public static boolean isRetriable(HttpStatusCode status) {
        return status.is5xxServerError() || status.value() == HttpStatus.TOO_MANY_REQUESTS.value();
    }
}
