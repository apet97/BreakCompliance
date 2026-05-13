-- P1.4 — overnight-shift bucketing setting. Admins choose how the engine
-- attributes a TimeEntry whose startAt/endAt span a calendar midnight:
--
--   start-day  — historical default; whole shift counted on the day it began.
--   end-day    — useful for "night shift attributed to the morning" workflows;
--                whole shift counted on the day it ended.
--
-- Null = use the engine's compiled default (start-day).

ALTER TABLE breakcompliance_workspace_settings
  ADD COLUMN IF NOT EXISTS night_shift_attribution TEXT;
