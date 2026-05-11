-- §21 — capture `userName` from the detailed-report response and surface
-- it on findings so the sidebar's pivot/checklist views show human names
-- instead of opaque user IDs. Both columns are nullable so backfill is
-- not required (findings created before this migration will render with
-- the userId fallback in the sidebar).
ALTER TABLE breakcompliance_time_entries  ADD COLUMN user_name TEXT;
ALTER TABLE breakcompliance_findings      ADD COLUMN user_name TEXT;
