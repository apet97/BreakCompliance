# Break Compliance

[![Build](https://github.com/apet97/BreakCompliance/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/apet97/BreakCompliance/actions/workflows/ci.yml)
[![Listing copy](https://img.shields.io/badge/marketplace-listing-blue)](docs/LISTING.md)
[![Privacy](https://img.shields.io/badge/docs-privacy-lightgrey)](docs/PRIVACY.md)
[![Security](https://img.shields.io/badge/docs-security-lightgrey)](docs/SECURITY.md)

Clockify marketplace add-on that reviews whether workspace users took required break time. Java 21 / Spring Boot 3.3 on the native [Clockify addon-java-sdk](https://github.com/clockify/addon-java-sdk), backed by PostgreSQL (durable tenant state) and Redis (webhook idempotency + per-workspace rate limiting). Designed for broad public-marketplace installation across many workspaces.

**Read-only.** No write scopes. The addon never creates, edits, or deletes time entries, never posts to Clockify, never messages users. See `docs/PRIVACY.md`.

## Improvement backlog

Active backlog of engine / UX / marketplace polish items is tracked in
[`docs/IMPROVEMENT_CHECKLIST.md`](docs/IMPROVEMENT_CHECKLIST.md). Items are
grouped P1–P6 by user-facing impact.

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+
- Docker (for Testcontainers-driven integration tests)

The Clockify Java SDK is **vendored** under `repo/com/cake/clockify/` and consumed via a `file://` Maven repository declared in `pom.xml`, so no GitHub Packages credential is required.

## Local setup

1. From this directory: `mvn verify`
2. Run locally: `mvn spring-boot:run`
3. Smoke test: `curl http://localhost:8080/healthz` → `{"status":"ok"}` and `curl http://localhost:8080/manifest` → manifest JSON.

## Project layout

```
.
├── pom.xml
├── src/main/java/me/apet97/breakcompliance/
│   ├── addon/{auth,lifecycle,manifest,ui,webhook}/  SDK touchpoints + iframe shells
│   ├── api/                                          /api/* controllers (session, findings,
│   │                                                 ingest, refresh-signals) + AddonTokenAuthFilter
│   ├── clockify/                                     REST client, rate limiter, report fetcher
│   ├── config/                                       Spring config + security headers + crypto guard
│   ├── domain/                                       Rule engine + preset registry
│   ├── persistence/{entities,repositories,crypto}/   JPA + AES-GCM-256 token codec
│   └── util/                                         Webhook path normalizer
├── src/main/resources/
│   ├── application.yaml                              Env-driven Spring config
│   ├── db/migration/V1__init.sql                     Flyway schema (V1–V10, additive only)
│   ├── logback-spring.xml                            Token-redacting log pattern
│   └── static/                                       sidebar.js, styles.css, icon.svg (64×64 designed mark)
├── docs/                                             Marketplace submission docs
│   ├── PRIVACY.md / SECURITY.md / DATA_RETENTION.md / LEGAL_NOTICES.md / SUPPORT.md
│   ├── LISTING.md                                    Source-of-truth listing copy
│   ├── LIVE_VALIDATION.md                            Production install/uninstall evidence
│   └── evidence/                                     Raw curl headers, manifest, metrics, psql, screenshot
├── CHANGELOG.md                                      Semver 0.1.0 onward
├── repo/com/cake/clockify/                           Vendored Clockify SDK (jar+pom)
│                                                     — see `pom.xml`'s `vendored-clockify-sdk` repository
└── .github/workflows/ci.yml                          Maven verify on PR + main
```

## Architecture

- **Spring Boot 3.3 + Spring MVC** — all routes are `@RestController` classes; the SDK is used as a manifest builder (`ClockifyManifest.v1_3Builder()`) + JWT verifier (`ClockifySignatureParser`), not as a routing framework.
- **PostgreSQL + Spring Data JPA + Flyway** — 12 workspace-scoped tables, composite primary keys with `workspace_id` leading every tenant-scoped table for schema-enforced isolation.
- **Redis + Spring Data Redis (Lettuce)** — webhook idempotency (24-hour SETNX TTL keyed by `sha256(eventType||body)`) and per-workspace Clockify-API rate limiting (50 req/sec/workspace, fixed-window).
- **AES-GCM-256 token codec** — every installation token and webhook auth token encrypted at rest; 12-byte IV per encrypt, `keyId` for rotation, fail-closed on any tamper.
- **3-check webhook auth** — RS256 signature, event-type header match, stored per-webhook authToken comparison.
- **Hosted on Railway** with managed Postgres + Redis add-ons and a custom-domain manifest URL.

## CI

GitHub Actions runs `mvn verify` on every push to `main` and every PR. No repo secrets are needed for the build — the Clockify SDK comes from the vendored `repo/` directory, Maven Central handles everything else.

## Production environment variables

| Variable | Required | Purpose |
|---|---|---|
| `ADDON_BASE_URL` | yes | Manifest's `baseUrl`. Must match the URL Clockify hits the addon at. |
| `PGHOST` / `PGPORT` / `PGDATABASE` / `PGUSER` / `PGPASSWORD` | yes | Postgres connection. Railway sets these as service-reference vars; the JDBC URL is built from them inside `application.yaml`. |
| `PG_SSLMODE` | optional | Defaults to `require`. Emergency knob — only flip to `disable`/`prefer` if Railway's managed Postgres can't negotiate SSL. |
| `REDISHOST` / `REDISPORT` / `REDISUSER` / `REDISPASSWORD` | yes | Redis connection. Railway sets these as service-reference vars; username is empty for local no-auth Redis. |
| `INSTALLATION_TOKEN_KEY` | yes | 64 hex characters → 256-bit AES key for the token codec. Generate via `openssl rand -hex 32`. |
| `INSTALLATION_TOKEN_KEY_ID` | optional | Active key id (defaults to `default`); supports rotation by mapping multiple ids → keys. |
| `CLOCKIFY_PUBLIC_KEY_PEM` | optional | Override for the Clockify RSA public key (PEM string). Default embedded in `ClockifyAddonConfig`. |
| `ENABLE_HSTS` | optional | Set `true` once a custom domain serves HTTPS end-to-end. `SecurityHeadersFilter` additionally requires `request.isSecure()` so an accidental HTTP slip can't pin a browser. |
| `EXTRA_FRAME_ANCESTORS` | optional | CSV of extra `frame-ancestors` for the CSP (e.g. `https://developer.clockify.me` during dev-portal testing). |
| `LOG_LEVEL_APP` | optional | Per-incident log level for `me.apet97.breakcompliance` (default `INFO`). Flip via `railway variables --set` without redeploy. |
| `SIDEBAR_TOKEN_MAX_IAT_AGE_SECONDS` / `IAT_CLOCK_SKEW_SECONDS` | optional | Sidebar JWT iat-replay window + tolerance. |

## Observability

`spring-boot-starter-actuator` + `micrometer-registry-prometheus` expose:

- `/actuator/health` — Railway healthcheck probe.
- `/actuator/prometheus` — `breakcompliance_webhook_received{event}`, `_webhook_duplicate{event}`, `_refresh_signals_processed{outcome}`, `_ingest_run_duration` (Timer), `_ingest_entries_processed`, `_ingest_run_failed{reason}` plus the standard JVM + Tomcat + Hikari + Spring scheduled-task series. Narrow access via reverse-proxy / IP allowlist before exposing externally.

Recommended scrape config + alert rules + Grafana dashboard layout in
[`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md).
