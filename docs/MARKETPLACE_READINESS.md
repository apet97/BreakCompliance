# Marketplace Readiness — Break Compliance for Clockify

> **Runtime:** Java 21 / Spring Boot 3.3 on the native [`addon-java-sdk`](https://github.com/clockify/addon-java-sdk) (Maven `com.cake.clockify:addon-sdk:1.5.3`), hosted on Railway with managed Postgres (durable tenant state) and Redis (webhook idempotency + per-workspace rate limiting).

A pre-flight checklist for submitting Break Compliance to the [Clockify Marketplace](https://marketplace.clockify.me). Items are concrete and grouped by submission requirement.

## Add-on identity

- **Name:** Break Compliance
- **Manifest key:** `break-compliance-jvm` (distinct from the older TypeScript-on-CF-Worker version under key `break-compliance`)
- **Schema version:** `1.3`
- **Minimal subscription plan:** `BASIC`
- **Surface:** Admin-only sidebar inside Clockify. Custom settings page (admin-only by Clockify convention).
- **Description:** "Review whether Clockify users took required break time. Checks explicit BREAK time entries against configurable rule templates (German ArbZG, California, Custom)."
- **Icon:** `/icon.svg` (replace before submission with the design-system-approved mark)

## Scopes (least-required)

| Scope | Why |
|---|---|
| `TIME_ENTRY_READ` | Read regular + BREAK entries via the Detailed Report. |
| `USER_READ` | List workspace users for the Settings page user picker. |
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
- `SETTINGS_UPDATED` — acknowledged with 200 (settings persistence flows through `/api/settings`).
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

## Open follow-ups before submission

- Replace the placeholder `icon.svg` with the design-system mark.
- Capture sidebar + settings screenshots (mobile + desktop widths) for the listing gallery.
- Soak a staging install in two test workspaces for the Phase 12 smoke checklist before flipping the production manifest URL.
