-- §25 — Composite indexes for the rule engine's hot-path queries.
--
-- Hot paths the engine walks:
--   1. FindingsService loads all time entries in a (workspaceId, dateRange)
--      window. After bucketing, the loop visits (workspaceId, userId,
--      startAt) order. The existing (workspace_id, start_at) index covers
--      the range scan but Postgres falls back to a full bucket sort for
--      the per-user partition. A (workspace_id, user_id, start_at) index
--      lets the planner skip the sort.
--
--   2. FindingsService.list() / FindingsController.list() filter findings
--      by (workspaceId, date BETWEEN ...). For workspaces with multi-user
--      ranges the planner also benefits from a per-user composite to
--      power the pivot-view group-by-user rendering.
--
-- Indexes are additive (CONCURRENTLY would require a non-transactional
-- migration; Flyway runs each script in its own transaction by default,
-- so CONCURRENTLY is dropped — these tables are small in tenant rows and
-- the AccessExclusive lock is short-lived). IF NOT EXISTS keeps the
-- migration replayable for environments that already have the indexes.

CREATE INDEX IF NOT EXISTS idx_breakcompliance_time_entries_by_ws_user_start
    ON breakcompliance_time_entries (workspace_id, user_id, start_at);

CREATE INDEX IF NOT EXISTS idx_breakcompliance_findings_by_ws_user_date
    ON breakcompliance_findings (workspace_id, user_id, date);
