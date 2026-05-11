# Operator Investigation Workflow

_Last updated: 2026-05-12._

This document describes the operator-side workflow for investigating a production incident affecting Break Compliance — for example, a workspace admin reporting unexpected findings or a webhook delivery loop.

## Trust boundaries

The operator has these credentials (none of which are end-user credentials):

- **Railway project access** — for env vars, service logs, and database console.
- **Postgres console** (Railway `psql` shell) — read/write on the `breakcompliance_*` tables.
- **Redis console** — read on transient counters; full flush during incidents only.

The operator does **not** have any customer's installation token. The MCP/inspection tooling, if used, uses a separate operator-owned API key — never a customer's installation token.

## Off-limits

- Modifying any `breakcompliance_*` row in a way that would silently change a customer-visible finding. Any change must be reproducible from a documented operator action.
- Querying Clockify directly with an installation token. If the operator needs a Detailed Report for diagnostics, the admin in the affected workspace can run a refresh through the Sidebar UI.

## Standard investigation steps

1. **Reproduce in staging.** Spin up an ephemeral Railway preview env, install the addon in the operator's own test workspace, and confirm the report bug locally. Production debugging is a last resort.
2. **Read service logs.** Logback emits structured info-level events for every lifecycle, webhook, ingestion, and rate-limit event. Tokens are masked at the appender; if the operator needs the raw token (extremely rare — for example, for vendor escalation), pull it from Postgres and decrypt via `TokenCodec` in a separate one-off script with audit-logged access.
3. **Inspect Postgres.**
   ```sql
   SELECT * FROM breakcompliance_installations WHERE workspace_id = '<ws>';
   SELECT * FROM breakcompliance_ingestion_runs WHERE workspace_id = '<ws>' ORDER BY created_at DESC LIMIT 10;
   SELECT * FROM breakcompliance_findings WHERE workspace_id = '<ws>' AND date BETWEEN ... ;
   ```
4. **Inspect Redis** if a webhook delivery is suspected of being deduplicated incorrectly:
   ```
   KEYS webhook:NEW_TIME_ENTRY:*
   TTL webhook:NEW_TIME_ENTRY:<hash>
   ```
   Flushing a specific key resends the next delivery through full processing.
5. **Mitigate.** If the root cause is a misconfigured template, ask the admin to adjust via the Settings UI. If the root cause is operator-side (a service-level bug), open a fix in the repo, deploy to staging, run the full mvn verify, then promote to production.
6. **Post-mortem.** Record what changed in `breakcompliance_audit_logs` (workspace_id null for system-level events). Customer-facing changes get a Slack/email post-mortem with timeline + remediation.

## Customer-data-touching ops

| Operation | When | How |
|---|---|---|
| Per-workspace wipe | Admin requests right-to-erasure | Run the SQL in `docs/DATA_RETENTION.md`. |
| Restore from backup | Database corruption / accidental delete | Railway Postgres snapshot restore (point-in-time within the snapshot retention window). |
| Rotate AES key | Suspected key leak | Generate new 64-hex value, set as `INSTALLATION_TOKEN_KEY`, deploy. Old key remains mapped while the operator decides on re-encryption strategy. |
| Force re-install | Suspected token compromise on Clockify's side | Ask the admin to uninstall and re-install; new install token is issued, old encrypted row is deleted by FK cascade. |

## What this workflow is not

- A backdoor. Operator access is logged in `breakcompliance_audit_logs` plus the Railway operational audit log. Any operator action on customer rows must be reproducible from a documented request.
- A replacement for the admin UI. Admins do their work through the Sidebar + Settings page; operator intervention is a last resort.
