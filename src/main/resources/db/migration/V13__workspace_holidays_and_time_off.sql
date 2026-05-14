-- P1.1 — workspace_holidays cache. Populated by HolidayFetcher during each
-- ingest run from GET /v1/workspaces/{ws}/holidays/in-period?start=&end=.
-- BreakRuleEngine.bucketEntries skips (userId, date) buckets covered by a
-- matching holiday — fills the gap when the workspace doesn't log explicit
-- type=HOLIDAY time entries.
--
-- Holiday scope: a row applies to a specific user when applies_to_user_id is
-- set; null = applies to the entire workspace (the typical Clockify "national
-- holiday" shape).
--
-- P1.2 — workspace_time_off cache. Populated from POST /v1/workspaces/{ws}/
--   time-off/requests (search variant) with an APPROVED filter. Engine
--   suppresses findings for the (userId, date) span between start_at and
--   end_at inclusive.
--
-- Both tables additive; nothing else queries them, so the worst-case failure
-- mode of a fetch error is "no suppression" — same as today.

CREATE TABLE IF NOT EXISTS breakcompliance_workspace_holidays (
  workspace_id        TEXT NOT NULL,
  date                DATE NOT NULL,
  applies_to_user_id  TEXT,
  name                TEXT,
  source_id           TEXT NOT NULL,
  ingested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_id, source_id, date, applies_to_user_id)
);

CREATE INDEX IF NOT EXISTS idx_breakcompliance_workspace_holidays_lookup
  ON breakcompliance_workspace_holidays (workspace_id, date);

CREATE TABLE IF NOT EXISTS breakcompliance_workspace_time_off (
  workspace_id  TEXT NOT NULL,
  source_id     TEXT NOT NULL,
  user_id       TEXT NOT NULL,
  start_at      TIMESTAMPTZ NOT NULL,
  end_at        TIMESTAMPTZ NOT NULL,
  status        TEXT NOT NULL,
  ingested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_id, source_id)
);

CREATE INDEX IF NOT EXISTS idx_breakcompliance_workspace_time_off_lookup
  ON breakcompliance_workspace_time_off (workspace_id, user_id, start_at);
