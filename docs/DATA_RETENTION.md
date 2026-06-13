# Data Retention — Break Compliance for Clockify

_Last updated: 2026-06-13._

## Per-table policy

| Table | Retention | Cleanup mechanism |
|---|---|---|
| `breakcompliance_installations` | Until uninstall | `DELETED` lifecycle deletes the row by `(workspace_id, addon_id)` and `WorkspaceDataDeletionService` wipes every workspace-scoped row in the same transaction. |
| `breakcompliance_webhook_auth_tokens` | Until uninstall | FK cascade on installation delete. |
| `breakcompliance_workspace_settings` | Until uninstall | Cleared by `WorkspaceDataDeletionService` on `DELETED`; otherwise preserved across `STATUS_CHANGED`. |
| `breakcompliance_rule_templates` | Until uninstall (engine-irrelevant since §18) | Cleared by `WorkspaceDataDeletionService`. The table is kept additively for back-compat; the engine evaluates against `WorkspaceSettings` via `synthesizeWorkspaceTemplate`. |
| `breakcompliance_template_assignments` | Until uninstall (engine-irrelevant since §18) | Cleared by `WorkspaceDataDeletionService`. |
| `breakcompliance_ingestion_runs` | Until uninstall (append-only audit) | Cleared by `WorkspaceDataDeletionService` on `DELETED`. |
| `breakcompliance_time_entries` | Replaced per ingestion | Upsert by `(workspace_id, source_entry_id)` on the next overlapping ingestion run; wiped on `DELETED`. |
| `breakcompliance_findings` | Replaced per evaluation range | Atomic delete-then-insert inside one transaction, scoped by `(workspace_id, date BETWEEN ...)`; wiped on `DELETED`. |
| `breakcompliance_finding_reviews` | While the finding exists | Service-level cascade with the underlying finding; bulk-cleared on `DELETED`. |
| `breakcompliance_refresh_signals` | Until uninstall | Signals flow `PENDING → CLAIMED → CONSUMED` (or `COALESCED`/`FAILED`) as the active consumer drains them; wiped on `DELETED`. |
| `breakcompliance_group_memberships` | Replaced per ingest | Snapshot rewrite by `(workspace_id, group_id, user_id)`; wiped on `DELETED`. |
| `breakcompliance_audit_logs` | Until uninstall | Cleared by `WorkspaceDataDeletionService` on `DELETED` alongside the rest of the workspace's app data. Verified live on 2026-05-13 for the then-current workspace-scoped table set (see `docs/LIVE_VALIDATION.md` §6); lifecycle tests now also pin the suppression-cache tables. |
| `breakcompliance_workspace_holidays` (P1.1) | Replaced per ingest window | Upsert by `(workspace_id, source_id, date, applies_to_user_id)` on the next overlapping ingest. Cleared on `DELETED` via `WorkspaceDataDeletionService`. |
| `breakcompliance_workspace_time_off` (P1.2) | Replaced per ingest window | Upsert by `(workspace_id, source_id)` on the next overlapping ingest. Cleared on `DELETED` via `WorkspaceDataDeletionService`. |

## DSAR (right of access) export — P6.1

Admins can fetch a JSON bundle of every row referencing a specific user via
`GET /api/dsar/{userId}` (workspace-scoped via the JWT, admin-gated). The
response includes time entries, findings, holiday assignments, and approved
time-off requests for that user, plus audit log rows where that user appears
as the audit `actor` in the current workspace — enough to satisfy GDPR Art. 15
/ Art. 20 without writing a custom query. Response is served with a
`Content-Disposition: attachment; filename="dsar-…json"` header so
operators can hand the user the file directly.

## Lifecycle-driven cleanup

- **DELETED** (uninstall): `LifecycleController` calls `WorkspaceDataDeletionService.deleteWorkspaceData(workspaceId)`, which issues per-table JPQL `DELETE`s for every workspace-scoped table (settings, templates, assignments, ingestion runs, time entries, findings, reviews, refresh signals, group memberships, audit logs, workspace holidays, workspace time off). The `Installation` row is then deleted by composite PK; the FK cascade clears `webhook_auth_tokens`. Tokens become invalid the instant Clockify fires DELETED — even before the wipe completes — because Clockify revokes them server-side. A **stale-DELETED guard** (P0 commit `029b0da`) compares the lifecycle event's JWT `iat` to the row's `installedAt`; a 24-hour retry of an old DELETED that arrives after a fresh reinstall is rejected so the new installation's data isn't wiped.
- **STATUS_CHANGED → INACTIVE**: the installation row is kept with `status='INACTIVE'`; `IngestionService` fail-fasts via `InstallationInactiveException` (mapped to a friendly `503 installation_inactive`) so no Clockify API call goes out and no run is recorded. No data is deleted.

## Operator-driven wipe procedure (rare — for emergencies only)

In practice, every right-to-erasure case is satisfied by uninstalling the add-on: `WorkspaceDataDeletionService` clears every workspace-scoped table in one transaction and `Installation` deletion cascades to `webhook_auth_tokens`. **Use the SQL below only when Clockify cannot fire DELETED** (e.g., the workspace was permanently deleted on Clockify's side and the lifecycle event never arrived):

```sql
BEGIN;
DELETE FROM breakcompliance_audit_logs         WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_findings           WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_finding_reviews    WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_time_entries       WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_ingestion_runs     WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_refresh_signals    WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_group_memberships  WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_template_assignments WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_rule_templates     WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_workspace_holidays WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_workspace_time_off WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_workspace_settings WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_installations      WHERE workspace_id = '<ws>';
COMMIT;
```

The DELETE on `installations` cascades to `webhook_auth_tokens`, so that table is implicitly cleared.

## Backups

Railway's Postgres add-on supports daily snapshots; operator should configure a retention of ≥ 30 days. Redis content is transient (TTL-bounded) and is not backed up.

## GDPR notes

- **Right-to-erasure** — fully covered by uninstalling the add-on. The DELETED lifecycle handler clears every workspace-scoped row in one transaction, then deletes the installation (which cascades to webhook tokens). No operator action is required for the normal case.
- **Data minimisation** — we never store IP addresses, never store webhook payloads, never log the installation token. The Detailed Report rows we cache contain only what's needed for rule evaluation.
- **Storage location** — Railway's region selection determines where Postgres + Redis run; pick a region appropriate to the workspace's regulatory requirements.
