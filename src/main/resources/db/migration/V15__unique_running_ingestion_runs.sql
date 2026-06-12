-- V15 — close the narrow prepareRun race at the database layer.
--
-- The service already checks for an existing RUNNING ingest before inserting
-- a new row, but two concurrent callers can both pass that read. This partial
-- unique index makes "one active run per workspace/date range" a database
-- invariant while still preserving historical COMPLETED/FAILED rows for audit.

-- If production already contains duplicate RUNNING rows for the same
-- workspace/range, keep the newest one active and retire the rest so the
-- invariant can be installed without blocking deploy. Claimed refresh signals
-- attached to retired runs are released for the consumer to retry.
WITH ranked AS (
    SELECT
        workspace_id,
        id,
        row_number() OVER (
            PARTITION BY workspace_id, date_range_start, date_range_end
            ORDER BY created_at DESC, id DESC
        ) AS rn
    FROM breakcompliance_ingestion_runs
    WHERE status = 'RUNNING'
),
retired AS (
    UPDATE breakcompliance_ingestion_runs runs
    SET status = 'FAILED',
        error_code = 'duplicate_running_run_retired',
        completed_at = now()
    FROM ranked
    WHERE runs.workspace_id = ranked.workspace_id
      AND runs.id = ranked.id
      AND ranked.rn > 1
    RETURNING runs.workspace_id, runs.id
)
UPDATE breakcompliance_refresh_signals signals
SET status = 'PENDING',
    ingestion_run_id = NULL
FROM retired
WHERE signals.workspace_id = retired.workspace_id
  AND signals.ingestion_run_id = retired.id
  AND signals.status = 'CLAIMED';

CREATE UNIQUE INDEX IF NOT EXISTS ux_ingestion_runs_one_running_per_workspace_range
    ON breakcompliance_ingestion_runs (workspace_id, date_range_start, date_range_end)
    WHERE status = 'RUNNING';
