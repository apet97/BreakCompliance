-- P6.2 — per-user exemption list. Engine skips evaluation for users whose id
-- is in this set, e.g. execs/contractors not subject to the workspace's break
-- policy. Stored as a comma-separated text blob to avoid a join table for a
-- field most workspaces will leave empty.
--
-- P3.3 — per-workspace tunable refresh debounce. Override of the
-- application-property default (20s) for chatty workspaces that prefer a
-- longer batch window, or quiet ones that want near-realtime refreshes.
-- Range enforced server-side by SettingsWarning + manifest field bounds.
--
-- Additive only — both columns nullable so existing rows continue to work
-- without backfill.

ALTER TABLE breakcompliance_workspace_settings
  ADD COLUMN IF NOT EXISTS exempt_user_ids TEXT,
  ADD COLUMN IF NOT EXISTS custom_refresh_debounce_seconds INTEGER;
