-- Adds workspace-level custom break policy that overrides the
-- rule-template thresholds when enabled. Designed to be single-tier
-- (one work threshold + one break minute requirement); secondary
-- thresholds remain template-only.

ALTER TABLE breakcompliance_workspace_settings
    ADD COLUMN custom_policy_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN custom_work_threshold_minutes INTEGER,
    ADD COLUMN custom_break_threshold_minutes INTEGER;
