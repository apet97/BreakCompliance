-- P1.3 — workspace toggle to scope the detailed-report fetch to APPROVED
-- entries only, so unsubmitted work-in-progress doesn't trigger findings.
--
-- P2.9 — admin overrides for engine severities. Each finding code can be
-- demoted from VIOLATION to WARNING / INFO so workspaces that want softer
-- signals can downgrade without disabling the rule. Stored as nullable
-- TEXT (the enum value); null / blank = keep the engine's default.
--
-- Additive only — every column nullable / defaulted so existing rows
-- continue to work without backfill.

ALTER TABLE breakcompliance_workspace_settings
  ADD COLUMN IF NOT EXISTS exclude_unsubmitted_entries BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS severity_override_missing_break TEXT,
  ADD COLUMN IF NOT EXISTS severity_override_insufficient_break TEXT,
  ADD COLUMN IF NOT EXISTS severity_override_max_continuous TEXT;
