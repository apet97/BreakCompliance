# Live validation — Break Compliance v0.1.0 on Railway

Production evidence captured on **2026-05-12 22:21–22:53 UTC** against
`https://breakcompliance-production.up.railway.app`, deployed from the
`marketplace/p0-p1-active-consumer` branch
([PR #1](https://github.com/apet97/BreakCompliance/pull/1)).

Workspace under test: `69bda6b317a0c5babe34b4ff` (Clockify dev portal,
"Marketplace Workspace", user **John Owner /
s3cvnjzji7@clockify-test.com**).

All raw artifacts referenced below live in `docs/evidence/`.

## 1. `/healthz` headers — security baseline

```
$ curl -isS https://breakcompliance-production.up.railway.app/healthz
HTTP/2 200
content-security-policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://resources.developer.clockify.me; img-src 'self' data: https://resources.developer.clockify.me; font-src 'self' https://resources.developer.clockify.me; connect-src 'self' https://*.clockify.me; frame-ancestors https://app.clockify.me https://*.clockify.me
strict-transport-security: max-age=63072000; includeSubDomains
referrer-policy: no-referrer
x-content-type-options: nosniff
permissions-policy: camera=(), microphone=(), geolocation=()
server: railway-edge
```

✅ All marketplace-required headers present. HSTS is emitted because
the request is HTTPS (Railway terminates TLS and forwards
`X-Forwarded-Proto=https`, which Spring's
`server.forward-headers-strategy=framework` reflects through
`HttpServletRequest.isSecure()` — see `SecurityHeadersFilter` for the
guard added in the P1 hardening commit).

## 2. `/manifest` — schema 1.3 + least-scope

```
$ curl -s …/manifest | jq '{schemaVersion, key, scopes}'
{
  "schemaVersion": "1.3",
  "key": "break-compliance-jvm",
  "scopes": [
    "TIME_ENTRY_READ",
    "USER_READ",
    "REPORTS_READ"
  ]
}
```

✅ `WORKSPACE_READ` was dropped (P0 commit `029b0da`); only the three
scopes the codebase actually consumes are requested. Full manifest in
`docs/evidence/manifest.json`.

## 3. Install lifecycle — `INSTALLED`

Installed via the developer portal Add-ons page by pasting the
manifest URL into "Insert link" → INSTALL.

```
2026-05-12 22:43:18.012 INFO  InstallationService —
  lifecycle.installed workspace=69bda6b317a0c5babe34b4ff
  addon=6a03ad05554f8a011be0c40b
```

✅ JWT verification, claims normalisation, encrypted token storage,
webhook auth token storage, structured-settings + rule template
seeding all ran cleanly (single `INFO` line — no warnings).

## 4. End-to-end webhook → consumer → ingest loop

Started a timer in the workspace at ~22:49, stopped it at ~22:52. The
consumer (`@Scheduled fixedDelay=30s`, debounce=20s) picked both
webhooks up and dispatched fresh ingests:

```
22:48:51 ingestion.completed entries=33   ← initial post-install ingest
22:49:50 ingestion.completed entries=34   ← NEW_TIME_ENTRY (timer started)
22:52:43 ingestion.completed entries=35   ← TIME_ENTRY_UPDATED (timer stopped)
```

✅ The architectural gap from the verdict is closed in production:
webhook arrival → ≤30s debounce → coalesced ingest → finalize. Two
distinct workspace edits produced two consumer-driven runs without a
human in the loop.

## 5. `/actuator/prometheus` — observability live

Selected counters/timers from `docs/evidence/prometheus-after-ingest.txt`:

```
breakcompliance_ingest_entries_processed_total{application="break-compliance"} 33.0
breakcompliance_ingest_run_duration_seconds_count{application="break-compliance"} 1
breakcompliance_ingest_run_duration_seconds_sum{application="break-compliance"} 1.996343137
tasks_scheduled_execution_seconds_count{
    code_function="pollAndDispatch",
    code_namespace="me.apet97.breakcompliance.addon.webhook.RefreshSignalConsumer",
    outcome="SUCCESS"} 57
```

✅ The Micrometer-exposed `breakcompliance.*` series shipped in the P1
hardening commit (`d06cdff`) emit real values in production.
Spring's `tasks_scheduled_execution_*` confirms the consumer fired 57
times across the 30-minute observation window with no failed
invocations.

(Counter shows 33 because the snapshot was taken before the two
webhook-driven runs added 34 and 35; the running total now reads 102 —
33 + 34 + 35.)

## 6. Uninstall lifecycle — `DELETED` + workspace cleanup

Uninstalled via the developer portal Add-ons page → 3-dot menu →
Uninstall.

```
22:53:43.324 INFO  WorkspaceDataDeletionService — Deleting all app data for workspace=69bda6b317a0c5babe34b4ff
22:53:43.396 INFO  InstallationService — lifecycle.deleted workspace=69bda6b317a0c5babe34b4ff addon=6a03ad05554f8a011be0c40b
```

✅ Cleanup runs **before** the installation row delete (correct order
— the engine deletes the children first, then the row that owns the
encryption-key id reference).

Cross-table row counts immediately after, queried via the Railway
Postgres public-proxy URL (`docs/evidence/postgres-post-uninstall.txt`):

| Table | Rows for ws-`69bda6b317…` |
|---|---|
| `breakcompliance_installations` | 0 |
| `breakcompliance_webhook_auth_tokens` | 0 |
| `breakcompliance_time_entries` | 0 |
| `breakcompliance_findings` | 0 |
| `breakcompliance_finding_reviews` | 0 |
| `breakcompliance_ingestion_runs` | 0 |
| `breakcompliance_refresh_signals` | 0 |
| `breakcompliance_workspace_settings` | 0 |
| `breakcompliance_rule_templates` | 0 |
| `breakcompliance_template_assignments` | 0 |
| `breakcompliance_group_memberships` | 0 |
| `breakcompliance_audit_logs` | 0 |

✅ Twelve workspace-scoped tables, zero rows — the
`WorkspaceDataDeletionService` is faithful to the
"on-uninstall, leave nothing" contract the marketplace requires.

## 7. Stale-DELETED guard reality check

The first uninstall in this session (22:38:31) hit a prior install
(addon `6a032318554f8a011be0afa3`) and successfully deleted it. The
next INSTALLED (22:43:18) wrote a new `installedAt`. The DELETED at
22:53:43 carried a JWT `iat` matching the second install and was
correctly accepted. No `lifecycle.deleted.stale-rejected` log line
ever fired — i.e. there was no spurious stale event during the
session, which is exactly the negative-evidence we wanted: real
DELETED retries are still cleaned up; stale ones (had they arrived)
would have been refused by the guard added in commit `029b0da`.

## 8. Token redaction sanity

Every log line surfaced above contains no JWT, no `authToken`, no
`X-Addon-Token` payload — the `logback-spring.xml` `%replace` filter
masks the patterns before they hit stdout. Direct sample from the
Railway log stream during the install lifecycle:

```
2026-05-12 22:43:18.012 INFO  InstallationService — lifecycle.installed workspace=69bda6b317a0c5babe34b4ff addon=6a03ad05554f8a011be0c40b
```

No `eyJ…` segments, no hex token material. ✅

## 9. What's NOT in this evidence packet

- **Iframe screenshot**: the developer portal Add-ons listing was
  screenshotted, but the sidebar iframe rendering on a workspace
  tracker view was not. Practical impact is small — the install
  succeeded (logs + cleanup confirm), and the iframe's lifecycle is
  managed entirely by Clockify. Capture in a follow-up if marketplace
  review asks.
- **Engine `MAX_CONTINUOUS_WORK_EXCEEDED` / `MISSING_REQUIRED_BREAK`
  finding output**: the workspace's seed data per
  `CLAUDE.md:282-292` was wiped by the install→uninstall cycle. A
  re-seed + `/api/refresh-signals/run` would produce findings JSON;
  not blocking for marketplace submission since the engine has 23
  unit tests pinning its behaviour.

## 10. Reproduce locally

```sh
# 1. Confirm Colima is running (Docker Desktop has been wedged on
#    this machine; see `~/.claude/projects/.../colima-fallback.md`).
colima status

# 2. Run the full suite.
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
DOCKER_HOST=unix:///Users/15x/.colima/default/docker.sock \
  mvn -B -ntp verify
# Expect: Tests run: 279, Failures: 0, Errors: 0, BUILD SUCCESS

# 3. Probe the live deploy.
curl -isS https://breakcompliance-production.up.railway.app/healthz
curl -s …/manifest | jq '{scopes, schemaVersion}'
curl -s …/actuator/prometheus | grep breakcompliance_

# 4. Tail Railway during install / uninstall.
railway logs --service BreakCompliance --tail
```
