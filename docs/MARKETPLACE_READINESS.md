# Marketplace Readiness — Break Compliance for Clockify

> **Runtime:** Java 21 / Spring Boot 3.3 on the native [`addon-java-sdk`](https://github.com/clockify/addon-java-sdk) (Maven `com.cake.clockify:addon-sdk:1.5.3`), hosted on Railway with managed Postgres (durable tenant state) and Redis (webhook idempotency + per-workspace rate limiting).

A pre-flight checklist for submitting Break Compliance to the [Clockify Marketplace](https://marketplace.clockify.me). Items are concrete and grouped by submission requirement.

## Add-on identity

- **Name:** Break Compliance
- **Manifest key:** `break-compliance-jvm` (distinct from the older TypeScript-on-CF-Worker version under key `break-compliance`)
- **Schema version:** `1.3`
- **Minimal subscription plan:** `BASIC`
- **Surface:** Admin-only sidebar iframe inside Clockify; configuration via Clockify's **native structured-settings page** (one tab "Break Compliance" with eleven admin-only fields — no custom `/settings` iframe).
- **Description:** "Review whether Clockify users took required break time. Checks explicit BREAK time entries against configurable rule templates (German ArbZG, California, Custom)."
- **Icon:** `/icon.svg` — 64×64 designed mark (Clockify-blue tile with a clock face paused at the 4-hour break threshold and a green compliance check overlay). Vector-only, no external resources, < 2 KB.

## Scopes (least-required)

| Scope | Why |
|---|---|
| `TIME_ENTRY_READ` | Read regular + BREAK entries via the Detailed Report. |
| `USER_READ` | Resolve user names for pivot-table rendering (`userName` on each finding). |
| `REPORTS_READ` | Detailed Report endpoint requires it. |
| `WORKSPACE_READ` | Resolve workspace metadata. |

No `_WRITE` scopes. The add-on never modifies time entries, never starts/stops timers, never edits workspace state. `bundle-grep` equivalent JUnit guard asserts the manifest never contains `_WRITE`.

## Security posture

- **Encrypted installation storage** — AES-GCM-256 token codec; every `Installation.authToken` and `WebhookAuthToken.authToken` row stores ciphertext only, with a per-row `keyId` to support key rotation. Plaintext never reaches the database.
- **JWT verification** — `ClockifySignatureParser` enforces RS256, `iss=clockify`, `type=addon`, `sub=break-compliance-jvm`; the filter additionally requires `exp` and rejects normalized claims missing `workspaceId` or `addonId`.
- **No raw token exposure** — Logback `%replace` converters mask JWT triplets and `authToken`/`X-Addon-Token`/`Clockify-Signature` values before any line reaches an appender.
- **Server-side install token** — every Clockify API call uses the verified per-workspace installation token. The browser receives only the user iframe token, scoped by Clockify to the viewing user.
- **Per-workspace API rate limiting** — Redis fixed-window counter caps outbound Clockify calls at 50 req/sec per workspace so one large tenant cannot starve others.
- **Webhook idempotency** — Redis `SETNX` with 24-hour TTL keyed by `sha256(eventType || 0x00 || body)` short-circuits retries to a 200 without side effects.

## Per-workspace isolation

- Every tenant-scoped table has `workspace_id` as the leading column of its composite primary key. Cross-workspace data probing is blocked at the schema level; `CrossWorkspaceIsolationTest` proves the workspace-scoped finders cannot leak across tenants.
- Body-supplied `workspaceId` is ignored. The auth filter derives `workspaceId` solely from the verified JWT and stores it as a request attribute the controllers read.

## Lifecycle handling

- `INSTALLED` — verify JWT, encrypt + persist install token, normalise + persist each webhook auth token, seed default workspace settings, return 200 within the 3-second budget.
- `DELETED` — atomic delete by `(workspace_id, addon_id)`; FK cascade on `webhook_auth_tokens` cleans up secondary rows. App-data (settings, templates, findings, etc.) is kept per `docs/DATA_RETENTION.md`.
- `SETTINGS_UPDATED` — accepts the canonical Clockify object wrapper `{workspaceId, addonId, settings: [...]}` (the live shape verified on the developer portal on 2026-05-11) and falls back to the legacy bare-array shape and to single `{id,value}` objects for resilience. Unknown shapes are recorded by `PayloadDriftLogger` and acknowledged 200 to avoid retry storms. Preset changes overwrite the eight threshold columns from the named preset before per-field edits land, so admins can flip preset + tweak one field in a single save.
- `STATUS_CHANGED` — flips `installations.status` between ACTIVE and INACTIVE.

## Submission checklist

| Item | Status |
|---|---|
| Manifest validates against schema 1.3 | ✓ `ManifestContractTest` |
| Lifecycle handlers respond within 3 s | ✓ Sync inserts; no I/O over 1 s |
| Webhook signature verified before any side effect | ✓ `WebhookAuthFilter` (3 checks: RS256 + event-type + stored authToken) |
| Re-installation is idempotent (DELETED → INSTALLED → INSTALLED) | ✓ Upserts in `InstallationService` |
| HTTPS only with TLS 1.2+ | ✓ Railway TLS terminator |
| Auth token never written to logs | ✓ Logback `%replace` converters + `Cache-Control: no-store` on `/api/*` |
| Per-workspace isolation tests | ✓ `CrossWorkspaceIsolationTest` |
| Privacy policy + retention policy + legal notices included | ✓ `docs/PRIVACY.md`, `docs/DATA_RETENTION.md`, `docs/LEGAL_NOTICES.md` |
| `INACTIVE` installations blocked from outbound Clockify calls | ✓ `InstallationInactiveException` → `503 installation_inactive` (sidebar banner) |
| Designed 64×64 marketplace icon | ✓ `src/main/resources/static/icon.svg` (vector, ~2.4 KB) |
| Sidebar UI/UX polish: active-template chip + thresholds popover, "Last checked" indicator, refresh button, dark-mode WCAG-AA, narrow-viewport responsive, theme-flicker fix | ✓ §24 |

## Listing copy (proposed)

> **Break Compliance** reviews whether Clockify workspace users took required break time. Choose a rule template (Custom basic, Germany ArbZG-style starter, California-style starter) or build your own, assign it to users or groups, and the add-on flags days where the qualifying break duration is below your policy. Findings are advisory and read-only — Break Compliance never edits time entries.

## Operator deployment (Railway)

```sh
# 1. Provision a Railway service + Postgres + Redis add-ons.
# 2. Set the env vars listed in README.md (ADDON_BASE_URL, INSTALLATION_TOKEN_KEY, etc.).
# 3. Push to main → GitHub Actions builds and Railway deploys via the deploy hook.
# 4. Attach the production custom domain; switch ENABLE_HSTS=true once TLS is verified.
# 5. Register the manifest URL with Clockify's developer portal (https://<custom-domain>/manifest).
```

## Live-test evidence

Run on 2026-05-11 against the developer portal workspace `69bda6b317a0c5babe34b4ff`:

- ✅ Install via manifest URL — `lifecycle.installed` logged at 21:35:16.
- ✅ Sidebar iframe mounts (`/sidebar?auth_token=…`, 1003×734) and renders the active-template chip + "Connected · {workspaceId}" status.
- ✅ Check Compliance — 15 entries ingested, 5 findings produced, pivot table rendered.
- ✅ Findings list (`/api/findings`) — `{findings: [...]}` shape matches sidebar destructuring.
- ✅ STATUS_CHANGED → INACTIVE — Check Compliance returns the friendly `503 installation_inactive` banner instead of a 500.
- ✅ SETTINGS_UPDATED with canonical object wrapper — payload is unwrapped and persisted (verified end-to-end after the 2026-05-12 §24 fix; the prior 21:40 + 21:44 400-responses no longer reproduce).
- ✅ X-Addon-Token header path (every `/api/*` success implies the header was sent; `auth_token` query parameter scrubbed via `history.replaceState`).
- ✅ CSP `frame-ancestors` admits `developer.clockify.me`.

## Open follow-ups before submission

- Capture sidebar + settings screenshots (laptop + mobile widths) for the listing gallery.
- Soak a staging install in two test workspaces for the Phase 12 smoke checklist before flipping the production manifest URL.
- Marketing assets (gallery, demo video) — out of scope for the repo; supplied at submission time.
