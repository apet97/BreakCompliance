# Privacy Policy — Break Compliance for Clockify

_Last updated: 2026-05-12._

## What Break Compliance is

Break Compliance is a server-side add-on running on Java 21 / Spring Boot 3.3, deployed on Railway with managed Postgres and Redis. The browser-side iframe (sidebar + settings page) calls the add-on's own server; it never calls Clockify directly. The server calls Clockify on the workspace's behalf using the verified installation token issued at INSTALLED time.

## What we store

### Installation context (Postgres `breakcompliance_installations`)

- `workspace_id` — the Clockify workspace id this installation belongs to.
- `addon_id` — the Clockify-generated addon id from `claims.addonId`.
- `auth_token_cipher` + `auth_token_key_id` — AES-GCM-256 ciphertext of the Clockify installation token, plus the key id used so we can rotate keys. **The plaintext token is never persisted and never logged.**
- `backend_url`, `reports_url` — Clockify region endpoints derived from the verified JWT claims.
- `installer_user_id` — Clockify user id of the admin who installed.
- `status`, `installed_at`, `updated_at` — operational fields.

### Webhook auth tokens (Postgres `breakcompliance_webhook_auth_tokens`)

- One row per webhook subscription, encrypted under the same AES-GCM-256 key. Foreign-keyed to the installation with `ON DELETE CASCADE`.

### App data (Postgres, workspace-scoped)

- `breakcompliance_workspace_settings` — admin-configured defaults (default template id, fallback detection toggle, timezone strategy).
- `breakcompliance_rule_templates` — built-in presets seeded per workspace on first read + admin-created CUSTOM templates.
- `breakcompliance_template_assignments` — user/group → template mappings.
- `breakcompliance_ingestion_runs` — append-only audit of Detailed Report fetches (date range, status, entries processed, error code).
- `breakcompliance_time_entries` — normalized Detailed Report rows (`source_entry_id`, user id, project id, start/end, duration, billable, tag names, raw JSON snapshot). **Source of truth lives in Clockify; this is a working copy used only for rule evaluation.**
- `breakcompliance_findings` — rule-engine output (severity, code, message, evidence JSONB).
- `breakcompliance_finding_reviews` — admin annotations on findings (OPEN/ACKNOWLEDGED/OVERRIDDEN + free-text note).
- `breakcompliance_refresh_signals` — advisory hints from webhook deliveries (event type only; payload contents not stored).
- `breakcompliance_group_memberships` — workspace-scoped (group, user) snapshot used by GROUP-typed template assignments.
- `breakcompliance_audit_logs` — operator-side event log; nullable `workspace_id` for system-level events.

### Redis (transient)

- Webhook idempotency keys (`webhook:{eventType}:{sha256(body)}`) with 24-hour TTL.
- Per-workspace API rate-limiter counters (`rl:{workspaceId}:{epochSecond}`) with 2-second TTL.

Both are cleanup-on-expiry; neither contains user-visible data.

## What we don't store

- The plaintext Clockify installation token.
- Browser cookies. The iframe is stateless across reloads; the iframe URL's `auth_token` is read once and stripped immediately via `History.replaceState`.
- IP addresses. Railway's edge sees them transiently; the add-on never persists them.
- Time-entry payload from webhook deliveries. The advisory signal records only the event type — the Detailed Report is the source of truth.

## Where the data lives

- **Postgres** (Railway managed instance) — all tables above.
- **Redis** (Railway managed instance) — transient keys above.
- **Application logs** (Logback to stdout) — request/response metadata, never auth tokens (Logback `%replace` converters mask JWT triplets and `authToken`/`X-Addon-Token`/`Clockify-Signature` values before lines reach the appender).

Railway's hosting infrastructure is governed by the operator's Railway account agreement.

## How long we keep it

See `docs/DATA_RETENTION.md` for per-table policy.

## How users exercise their rights

- **Right to know** — workspace admins see every row the add-on stored about their workspace via the Settings + Sidebar UI. Operator can produce a CSV/JSON export on request.
- **Right to erasure** — uninstall the add-on (DELETED lifecycle) to clear installation + webhook auth tokens immediately; app-data wipe is operator-side per the procedure in `docs/DATA_RETENTION.md`.
- **Right to rectification** — workspace admins can edit templates, assignments, and finding review notes directly through the Settings UI.

## Contact

For privacy questions, security disclosures, data-subject requests, or any
other matter relating to the add-on's handling of Clockify workspace data,
contact **petkovic.aleksandar037@gmail.com**. The operator commits to
acknowledging requests within 7 business days.
