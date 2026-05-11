-- The workspace now has ONE active rule template, always. The
-- structured-settings dropdown becomes a preset-loader: picking a
-- preset overwrites the threshold columns with that preset's values.
-- This column tracks the most recently applied preset so the
-- SETTINGS_UPDATED handler can detect a preset change vs. a manual
-- field edit and only overwrite when the preset itself changed.
ALTER TABLE breakcompliance_workspace_settings
    ADD COLUMN applied_preset_key TEXT NOT NULL DEFAULT 'custom-basic';
