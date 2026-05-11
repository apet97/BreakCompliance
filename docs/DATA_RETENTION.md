# Data Retention — Break Compliance for Clockify

_Last updated: 2026-05-11._

## Per-table policy

| Table | Retention | Cleanup mechanism |
|---|---|---|
| `breakcompliance_installations` | Until uninstall | `DELETED` lifecycle deletes the row by `(workspace_id, addon_id)`. |
| `breakcompliance_webhook_auth_tokens` | Until uninstall | FK cascade on installation delete. |
| `breakcompliance_workspace_settings` | Indefinite | Manual operator wipe; preserved across reinstall. |
| `breakcompliance_rule_templates` | Indefinite | Admin DELETE on `/api/templates`; built-in presets re-seed on the next list call. |
| `breakcompliance_template_assignments` | Indefinite | Admin DELETE on `/api/assignments`; cascaded when the underlying template is deleted. |
| `breakcompliance_ingestion_runs` | Indefinite (append-only audit) | Manual operator wipe. |
| `breakcompliance_time_entries` | Replaced per ingestion | Upsert by `(workspace_id, source_entry_id)` on the next overlapping ingestion run. |
| `breakcompliance_findings` | Replaced per evaluation range | Atomic delete-then-insert inside one transaction, scoped by `(workspace_id, date BETWEEN ...)`. |
| `breakcompliance_finding_reviews` | While the finding exists; orphan rows pruned on a periodic sweep | Cascade is handled at service level; orphan retention is configurable via env. |
| `breakcompliance_refresh_signals` | Indefinite | Status flip to `ACKNOWLEDGED` after the runner processes the signal. |
| `breakcompliance_group_memberships` | Replaced per ingest | Snapshot rewrite by `(workspace_id, group_id, user_id)`. |
| `breakcompliance_audit_logs` | Indefinite (operational history) | Manual operator wipe. |

## Lifecycle-driven cleanup

- **DELETED** (uninstall): the `Installation` row is deleted by composite PK; the FK cascade clears `webhook_auth_tokens`. App-data tables are preserved by default so a reinstall doesn't lose admin configuration; the operator can wipe them explicitly via the procedure below.
- **STATUS_CHANGED → INACTIVE**: the installation row is kept with `status='INACTIVE'`; ingestion calls become inert until status flips back. No data is deleted.

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
