# Observability — Break Compliance

Operational reference for running the addon in production. Source of truth for
metric names is `src/main/java/me/apet97/breakcompliance/config/MetricsConfig.java`.

## Scrape endpoint

`spring-boot-starter-actuator` + `micrometer-registry-prometheus` expose:

```
GET /actuator/prometheus
```

In `application.yaml` the endpoint is gated by
`management.endpoints.web.exposure.include` — confirm `prometheus` is listed
before pointing a scrape at it.

Recommended Prometheus job:

```yaml
- job_name: breakcompliance
  metrics_path: /actuator/prometheus
  scrape_interval: 30s
  static_configs:
    - targets: ['breakcompliance-production.up.railway.app:443']
      labels:
        service: breakcompliance
        env: production
```

Railway terminates TLS at the edge and assigns a public hostname per service —
swap the target above for whatever `railway domain` prints if you've not
configured a custom host.

## Metrics catalogue

All names share the `breakcompliance.` prefix and use Micrometer's dotted-
lowercase convention. Tags are listed under each entry.

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `breakcompliance.webhook.received` | counter | `event` | Authenticated webhook deliveries (NEW / UPDATED / DELETED). |
| `breakcompliance.webhook.duplicate` | counter | `event` | Webhook deliveries skipped by Redis SETNX dedupe. A sustained spike means Clockify is retrying — investigate response latency. |
| `breakcompliance.refresh.signals.processed` | counter | `outcome` | Refresh-signal outcomes: `dispatched`, `coalesced`, `no_installation`, `inactive`, `failed`. |
| `breakcompliance.ingest.run.duration` | timer | — | End-to-end ingest-run duration. Successes only; failed runs go to `breakcompliance.ingest.run.failed`. |
| `breakcompliance.ingest.entries.processed` | counter | — | Time entries upserted into Postgres during finalize. |
| `breakcompliance.ingest.run.failed` | counter | `reason` | Failed ingest runs. The tag value is the upstream class name or HTTP status (e.g. `ClockifyApi:401`). |

## Recommended alerts

Threshold values assume a single workspace's traffic. Scale up if you run
multi-tenant.

```yaml
groups:
- name: breakcompliance
  rules:

  - alert: BreakComplianceIngestFailureSpike
    expr: rate(breakcompliance_ingest_run_failed_total[15m]) > 0.05
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: >-
        Break Compliance ingest run failure rate above 0.05/s for 10m
        (workspace tokens expired? Clockify reports down?).

  - alert: BreakComplianceWebhookDuplicateBurst
    expr: rate(breakcompliance_webhook_duplicate_total[15m])
          > 5 * rate(breakcompliance_webhook_received_total[15m])
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: >-
        Webhook dedupe rate is >5× the receive rate — Clockify is retrying us
        because we're responding slowly.

  - alert: BreakComplianceNoIngestRecently
    expr: time() - max(breakcompliance_ingest_run_duration_seconds_count
                       offset 1h) > 3600
    for: 5m
    labels:
      severity: info
    annotations:
      summary: >-
        No successful ingest run in the last hour. Expected if the workspace
        is idle; investigate if the dev workspace is active.
```

## Grafana dashboard

A minimal 4-panel dashboard:

1. **Webhook throughput** — `rate(breakcompliance_webhook_received_total[5m])`
   stacked by `event`.
2. **Webhook duplicate ratio** —
   `rate(breakcompliance_webhook_duplicate_total[5m])
    / rate(breakcompliance_webhook_received_total[5m])`.
3. **Refresh outcomes** —
   `rate(breakcompliance_refresh_signals_processed_total[5m])` stacked by
   `outcome`.
4. **Ingest run latency** — `histogram_quantile(0.95,
   rate(breakcompliance_ingest_run_duration_seconds_bucket[10m]))` plus the
   `_count` series for "runs per period".

Export the dashboard JSON under `docs/dashboards/breakcompliance.json` when
finalised.

## Health endpoints

`spring-boot-starter-actuator` also exposes `/actuator/health` with
Postgres + Redis sub-indicators. Railway's healthcheck should target the same
URL — restarts fire if either dependency is down for more than the configured
grace.

## Log queries

The Logback pattern in `logback-spring.xml` redacts `authToken`,
`X-Addon-Token`, and `Clockify-Signature` values to `***`. Useful searches:

| What | Search |
|---|---|
| Failed ingest runs | `railway logs --service BreakCompliance | rg 'ingest.run.failed'` |
| Coalesce window capped | `railway logs --service BreakCompliance | rg 'window-capped'` |
| Connection leaks | `railway logs --service BreakCompliance | rg 'Connection leak detection'` |
| Settings drift | `railway logs --service BreakCompliance | rg 'PayloadDriftLogger'` |
