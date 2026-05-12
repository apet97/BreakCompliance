-- V10 — extend refresh-signal state machine for the webhook-driven consumer
--
-- Phase 2 of the marketplace-readiness work adds an active consumer that
-- polls PENDING signals past a debounce window, coalesces them by
-- workspace + date range, and dispatches a single async ingest. The
-- signal lifecycle gains four new terminal states (CLAIMED, CONSUMED,
-- FAILED, COALESCED) so the consumer can record which run owns a signal
-- and operators can audit retries. ACKNOWLEDGED stays as a vestigial
-- value — it was the original "user saw this in the sidebar" marker; the
-- consumer never writes it, but pre-V10 rows can still carry it.
--
-- The {@code ingestion_run_id} column is the back-pointer from a signal
-- to the run that consumed/coalesced it. Nullable: PENDING rows have no
-- run yet, and ACKNOWLEDGED rows from before V10 were never associated
-- with one. A partial index on PENDING accelerates the every-30s consumer
-- poll (the workspace+received_at index from V1 doesn't help the
-- "all pending older than N seconds" scan the consumer performs).

ALTER TABLE breakcompliance_refresh_signals
    DROP CONSTRAINT breakcompliance_refresh_signals_status_check;

ALTER TABLE breakcompliance_refresh_signals
    ADD CONSTRAINT breakcompliance_refresh_signals_status_check
    CHECK (status IN ('PENDING','ACKNOWLEDGED','CLAIMED','CONSUMED','FAILED','COALESCED'));

ALTER TABLE breakcompliance_refresh_signals
    ADD COLUMN ingestion_run_id TEXT;

CREATE INDEX idx_breakcompliance_refresh_signals_pending_received
    ON breakcompliance_refresh_signals (received_at)
    WHERE status = 'PENDING';
