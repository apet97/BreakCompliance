# Operations Runbook — Break Compliance

_Last updated: 2026-06-12._

Break Compliance is a read-only Clockify marketplace add-on hosted on Railway.
This runbook is for deploys, rollbacks, monitoring, key rotation, incident
response, and data erasure. Do not paste tokens, database URLs, Railway secrets,
or Clockify API keys into this repository.

## Production service

| Item | Value |
|---|---|
| Service | Railway project `break-compliance`, service `BreakCompliance`, env `production` |
| Base URL | `https://breakcompliance-production.up.railway.app` |
| Health | `https://breakcompliance-production.up.railway.app/healthz` |
| Manifest | `https://breakcompliance-production.up.railway.app/manifest` |
| Metrics | `/actuator/prometheus` |

## Required environment variables

| Variable | Purpose |
|---|---|
| `ADDON_BASE_URL` | Public base URL used in the generated Clockify manifest. |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | Railway Postgres connection values. |
| `PG_SSLMODE` | Defaults to `require`; emergency knob only. Keep `tcpKeepAlive=true` in the JDBC URL. |
| `REDISHOST`, `REDISPORT`, `REDISUSER`, `REDISPASSWORD` | Railway Redis connection values. |
| `INSTALLATION_TOKEN_KEY` | Active 64-hex AES-GCM key for installation/webhook token encryption. Must not be all-zero or legacy `...aa`. |
| `INSTALLATION_TOKEN_KEY_ID` | Active key id in `breakcompliance.crypto.keys`; defaults to `default`. |
| `LOG_LEVEL_APP` | App logger level; default `INFO`, temporary incident value `DEBUG`. |
| `EXTRA_FRAME_ANCESTORS` | Optional CSP additions for Clockify-controlled staging/dev hosts. |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | Optional marketplace evidence allowlist. Leave empty in normal production. |
| `ENABLE_HSTS` | Keep aligned with TLS deployment; HSTS is also gated by secure requests. |
| `SIDEBAR_TOKEN_MAX_IAT_AGE_SECONDS`, `IAT_CLOCK_SKEW_SECONDS` | Sidebar JWT replay window and clock-skew tolerance. |

## Deploy

1. Verify the local artifact first:

   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
     mvn -B -ntp test
   ```

   V15 installs the "one RUNNING ingest per workspace/date range" invariant.
   If duplicate RUNNING rows already exist, the migration keeps the newest row
   active and marks older duplicates `FAILED` with
   `error_code=duplicate_running_run_retired`. Any CLAIMED refresh signals
   attached to retired duplicate runs are released back to `PENDING`.

2. Deploy intentionally. A push to `main` does not auto-deploy:

   ```sh
   railway up --service BreakCompliance --ci
   ```

3. Verify the deployed service:

   ```sh
   curl -isS https://breakcompliance-production.up.railway.app/healthz
   curl -sS https://breakcompliance-production.up.railway.app/manifest \
     | jq '{schemaVersion,key,scopes,components,settings}'
   railway logs --service BreakCompliance
   ```

Pass criteria: `/healthz` returns 200, manifest scopes are exactly
`REPORTS_READ`, `TIME_ENTRY_READ`, `USER_READ`, and logs show no token/JWT
material.

## Rollback

1. Identify the last known-good Railway deployment in the Railway UI or CLI.
2. Roll back to that deployment from Railway.
3. Verify:

   ```sh
   curl -isS https://breakcompliance-production.up.railway.app/healthz
   curl -sS https://breakcompliance-production.up.railway.app/manifest | jq '{key,scopes}'
   railway logs --service BreakCompliance
   ```

4. Confirm no migration incompatibility. Migrations must remain additive; do not
   roll back code that expects a dropped column or constraint.

## Temporary incident debug

Raise logging only for the incident window, then restore it:

```sh
railway variables --set LOG_LEVEL_APP=DEBUG
railway logs --service BreakCompliance
railway variables --set LOG_LEVEL_APP=INFO
```

Never hardcode `DEBUG` in `logback-spring.xml`, and never log decrypted
installation/webhook tokens.

## Monitoring

Check these during smoke tests and incidents:

- `/actuator/health`: Spring/Railway health.
- `/actuator/prometheus`:
  - `breakcompliance_webhook_received{event=...}`
  - `breakcompliance_webhook_duplicate{event=...}`
  - `breakcompliance_refresh_signals_processed{outcome=...}`
  - `breakcompliance_ingest_run_duration_seconds_*`
  - `breakcompliance_ingest_entries_processed_total`
  - `breakcompliance_ingest_run_failed_total{reason=...}`
- Railway logs:
  - lifecycle install/delete lines
  - `refresh.consumer.*`
  - `ingestion.completed`
  - `Connection leak detection triggered`
  - repeated Clockify 429/5xx retries

If Hikari leak detection fires, fix the leaked DB access path. Do not raise the
pool size or disable leak detection as the first response.

## Key rotation

Generate a new key with:

```sh
openssl rand -hex 32
```

The value must be 64 hex characters, not all-zero, and not the legacy `...aa`
test key. Current code can decrypt multiple configured key ids through
`breakcompliance.crypto.keys`, but there is no automated re-encryption job in
this repo. Rotation options:

1. **Dual-key rotation:** add the new key under a new key id, set
   `INSTALLATION_TOKEN_KEY_ID` to that id, deploy, then re-encrypt existing
   rows with a controlled one-off job before removing the old key.
2. **Emergency containment:** uninstall/reinstall affected workspaces after
   deploying the new key so Clockify issues fresh installation/webhook tokens.

For suspected token exposure, prefer emergency containment unless a reviewed
one-off re-encryption procedure is ready.

## Data erasure

Normal erasure is uninstall-driven: Clockify sends `DELETED`, and
`WorkspaceDataDeletionService` clears all workspace-scoped rows in one
transaction before deleting the installation row. See `docs/DATA_RETENTION.md`
for the table-by-table policy and emergency manual SQL.

After erasure, verify workspace counts are zero for installations, settings,
time entries, findings, reviews, ingestion runs, refresh signals, templates,
assignments, group memberships, audit logs, and webhook tokens.

## Incident playbooks

### Token exposure

1. Stop debug logging and preserve current logs for review.
2. Rotate `INSTALLATION_TOKEN_KEY`.
3. Uninstall/reinstall affected workspaces or execute a reviewed dual-key
   re-encryption plan.
4. Verify no token material appears in `railway logs`.
5. Notify affected workspace admins through the support process in `docs/SUPPORT.md`.

### Clockify API outage or repeated 429s

1. Check `breakcompliance_ingest_run_failed_total{reason=...}` and Railway logs.
2. Confirm failures are from Clockify status codes, not local auth/decryption.
3. Leave Redis-backed rate limiting fail-closed.
4. Retry after Clockify recovers; do not add new Clockify endpoints or scopes.

### Redis outage

Redis protects webhook idempotency and API rate limiting. Current behavior is
fail-closed: webhook retries should bubble, and ingestion should abort rather
than risk duplicate processing or rate-limit violations. Restore Redis, then
watch pending webhook deliveries recover.

### Postgres outage

Postgres is authoritative for installation state and cached report rows. Keep
the service unavailable until Postgres is healthy; do not fall back to in-memory
state. Verify `/actuator/health`, then run a small authenticated sidebar smoke
test.

### Stuck RUNNING ingest

`IngestionRunReaper` marks runs older than the configured threshold as `FAILED`
and releases claimed refresh signals. If stuck runs repeat, inspect executor
capacity, Clockify latency/retries, and Hikari leak warnings before changing
pool sizes. If you see `duplicate_running_run_retired`, V15 cleaned up
pre-existing duplicate RUNNING rows while installing the partial unique index;
inspect the surrounding logs, but the remaining newest RUNNING row is the one
the app will expose as the in-flight run. CLAIMED signals attached to retired
duplicates are released back to PENDING for the next consumer poll.
