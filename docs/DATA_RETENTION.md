# Data Retention — Break Compliance for Clockify

_Last updated: 2026-05-12._

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
| `breakcompliance_refresh_signals` | Until uninstall | Status flips to `ACKNOWLEDGED` after the runner processes the signal; wiped on `DELETED`. |
| `breakcompliance_group_memberships` | Replaced per ingest | Snapshot rewrite by `(workspace_id, group_id, user_id)`; wiped on `DELETED`. |
| `breakcompliance_audit_logs` | Indefinite (operational history) | Manual operator wipe — kept across `DELETED` so the audit trail survives reinstall. |

## Lifecycle-driven cleanup

- **DELETED** (uninstall): `LifecycleController` calls `WorkspaceDataDeletionService.deleteWorkspaceData(workspaceId)`, which issues per-table JPQL `DELETE`s for every workspace-scoped table (settings, templates, assignments, ingestion runs, time entries, findings, reviews, refresh signals, group memberships, audit logs are intentionally kept). The `Installation` row is then deleted by composite PK; the FK cascade clears `webhook_auth_tokens`. Tokens become invalid the instant Clockify fires DELETED — even before the wipe completes — because Clockify revokes them server-side.
- **STATUS_CHANGED → INACTIVE**: the installation row is kept with `status='INACTIVE'`; `IngestionService` fail-fasts via `InstallationInactiveException` (mapped to a friendly `503 installation_inactive`) so no Clockify API call goes out and no run is recorded. No data is deleted.

## Operator-driven wipe procedure

Connect with Railway's `psql` console (or `pg_dump` for an export first) and run:

```sql
DELETE FROM breakcompliance_findings           WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_finding_reviews    WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_time_entries       WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_ingestion_runs     WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_refresh_signals    WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_group_memberships  WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_template_assignments WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_rule_templates     WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_workspace_settings WHERE workspace_id = '<ws>';
DELETE FROM breakcompliance_installations      WHERE workspace_id = '<ws>';
```

The transaction can be wrapped to make the wipe atomic. The DELETE on `installations` cascades to `webhook_auth_tokens`, so that table is implicitly cleared.

## Backups

Railway's Postgres add-on supports daily snapshots; operator should configure a retention of ≥ 30 days. Redis content is transient (TTL-bounded) and is not backed up.

## GDPR notes

- **Right-to-erasure** — covered by the per-workspace wipe procedure plus the DELETED lifecycle. An admin requesting erasure should uninstall the add-on (clears tokens) and ask the operator to run the wipe SQL above (clears app data).
- **Data minimisation** — we never store IP addresses, never store webhook payloads, never log the installation token. The Detailed Report rows we cache contain only what's needed for rule evaluation.
- **Storage location** — Railway's region selection determines where Postgres + Redis run; pick a region appropriate to the workspace's regulatory requirements.
