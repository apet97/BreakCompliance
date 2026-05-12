-- V9 — persist cross-field validation warnings on workspace_settings
--
-- The lifecycle handler accepts any value Clockify pushes (rejecting the
-- delivery would only trigger a retry loop), and the engine silently
-- substitutes preset defaults for null/≤0 columns. Admins who set
-- inconsistent thresholds (e.g. required break > work threshold) used to
-- see clean-looking findings produced from fallback math, never knowing
-- their save was nonsensical.
--
-- This column stores a JSON-encoded list of {code, message} warnings
-- produced by InstallationService.validateThresholdConsistency at save
-- time. SessionController surfaces them on /api/session so the sidebar
-- can render a banner. Nullable: settings rows from before this column
-- existed simply have no warnings to show.

ALTER TABLE breakcompliance_workspace_settings
    ADD COLUMN validation_warnings TEXT;
