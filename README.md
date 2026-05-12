# Break Compliance

Clockify marketplace add-on that reviews whether workspace users took required break time. Java 21 / Spring Boot 3.3 on the native [Clockify addon-java-sdk](https://github.com/clockify/addon-java-sdk), backed by PostgreSQL (durable tenant state) and Redis (webhook idempotency + per-workspace rate limiting). Designed for broad public-marketplace installation across many workspaces.

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+
- Docker (for Testcontainers-driven integration tests)
- A GitHub Personal Access Token with `read:packages` scope. The Clockify Java SDK is published to GitHub Packages, which requires authenticated reads even for public packages.

## Local setup

1. Copy `.github/settings.xml.template` to `~/.m2/settings.xml` and fill in your GitHub username and PAT.
2. From this directory: `mvn verify`
3. Run locally: `mvn spring-boot:run`
4. Smoke test: `curl http://localhost:8080/healthz` → `{"status":"ok"}` and `curl http://localhost:8080/manifest` → manifest JSON.

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
│   ├── db/migration/V1__init.sql                     Flyway schema (V1–V9, additive only)
│   ├── logback-spring.xml                            Token-redacting log pattern
│   └── static/                                       sidebar.js, styles.css, icon.svg (64×64 designed mark)
├── docs/                                             Marketplace submission docs
└── .github/
    ├── workflows/ci.yml                              Maven verify on PR + main
    └── settings.xml.template                         GitHub Packages auth template
```

## Architecture

- **Spring Boot 3.3 + Spring MVC** — all routes are `@RestController` classes; the SDK is used as a manifest builder (`ClockifyManifest.v1_3Builder()`) + JWT verifier (`ClockifySignatureParser`), not as a routing framework.
- **PostgreSQL + Spring Data JPA + Flyway** — 12 workspace-scoped tables, composite primary keys with `workspace_id` leading every tenant-scoped table for schema-enforced isolation.
- **Redis + Spring Data Redis (Lettuce)** — webhook idempotency (24-hour SETNX TTL keyed by `sha256(eventType||body)`) and per-workspace Clockify-API rate limiting (50 req/sec/workspace, fixed-window).
- **AES-GCM-256 token codec** — every installation token and webhook auth token encrypted at rest; 12-byte IV per encrypt, `keyId` for rotation, fail-closed on any tamper.
- **3-check webhook auth** — RS256 signature, event-type header match, stored per-webhook authToken comparison.
- **Hosted on Railway** with managed Postgres + Redis add-ons and a custom-domain manifest URL.

## CI

GitHub Actions runs `mvn verify` on every push to `main` and every PR. The workflow writes `~/.m2/settings.xml` at job start using the `GH_PACKAGES_USER` + `GH_PACKAGES_PAT` repo secrets so the addon-sdk artifact can be pulled from GitHub Packages.

## Production environment variables

| Variable | Required | Purpose |
|---|---|---|
| `ADDON_BASE_URL` | yes | Manifest's `baseUrl`. Must match the URL Clockify hits the addon at. |
| `DATABASE_URL` | yes | JDBC connection string for Postgres. Railway sets this automatically. |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | yes | Postgres credentials. |
| `REDIS_HOST` / `REDIS_PORT` | yes | Redis connection details. Railway sets these automatically. |
| `INSTALLATION_TOKEN_KEY` | yes | 64 hex characters → 256-bit AES key for the token codec. Generate via `openssl rand -hex 32`. |
| `INSTALLATION_TOKEN_KEY_ID` | optional | Active key id (defaults to `default`); supports rotation. |
| `CLOCKIFY_PUBLIC_KEY_URL` | optional | Override for the Clockify RSA public-key endpoint. |
| `ENABLE_HSTS` | optional | Set `true` once a custom domain serves HTTPS end-to-end. |
| `EXTRA_FRAME_ANCESTORS` | optional | CSV of extra `frame-ancestors` for the CSP (e.g. `https://developer.clockify.me` during dev-portal testing). |
