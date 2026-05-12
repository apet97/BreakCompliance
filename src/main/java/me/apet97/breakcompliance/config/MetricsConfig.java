package me.apet97.breakcompliance.config;

/**
 * Canonical metric names. Centralised so producers + scrape-config
 * stay aligned and grep'ing for usage is straightforward. Naming
 * follows Micrometer / Prometheus convention: dotted, lowercase,
 * application prefix.
 *
 * <p>This class holds no beans — the {@code spring-boot-starter-actuator}
 * already supplies a {@code MeterRegistry} that producers inject
 * directly. Tags are documented next to each name; instrumentation
 * sites should reuse the constants here.
 */
public final class MetricsConfig {

    private MetricsConfig() {
    }

    /**
     * Webhook deliveries received and authenticated.
     * Tags: {@code event}=NEW_TIME_ENTRY|TIME_ENTRY_UPDATED|TIME_ENTRY_DELETED.
     */
    public static final String WEBHOOK_RECEIVED = "breakcompliance.webhook.received";

    /**
     * Webhook deliveries deduped by Redis SETNX. A spike here points at
     * Clockify retrying us due to slow responses — investigate latency.
     * Tags: {@code event}.
     */
    public static final String WEBHOOK_DUPLICATE = "breakcompliance.webhook.duplicate";

    /**
     * Refresh signals processed by the active consumer.
     * Tags: {@code outcome}=dispatched|coalesced|no_installation|inactive|failed.
     */
    public static final String REFRESH_SIGNALS_PROCESSED = "breakcompliance.refresh.signals.processed";

    /**
     * Total time spent inside {@code IngestionService.executeRun}
     * (fetch + finalize). Reports successes only — failed runs hit a
     * separate counter.
     */
    public static final String INGEST_RUN_DURATION = "breakcompliance.ingest.run.duration";

    /** Time entries upserted into Postgres during {@code finalizeRun}. */
    public static final String INGEST_ENTRIES_PROCESSED = "breakcompliance.ingest.entries.processed";

    /**
     * Ingest runs ending in {@code FAILED}. Tags: {@code reason} —
     * typically the upstream class name or HTTP status.
     */
    public static final String INGEST_RUN_FAILED = "breakcompliance.ingest.run.failed";
}
