-- V8 — broaden ingestion_runs.status CHECK to allow RUNNING
--
-- The ingest flow is now split across three phases (prepareRun / fetch /
-- finalizeRun) so the long Clockify HTTP call no longer holds a DB
-- connection. The prepare phase inserts a row in RUNNING state; finalize
-- updates it to COMPLETED or FAILED. If the JVM dies between phases the
-- row stays RUNNING — observability win for operators chasing stuck runs.
--
-- The change is additive: existing COMPLETED/FAILED rows still satisfy
-- the new constraint, and no data needs to be migrated.

ALTER TABLE breakcompliance_ingestion_runs
    DROP CONSTRAINT breakcompliance_ingestion_runs_status_check;

ALTER TABLE breakcompliance_ingestion_runs
    ADD CONSTRAINT breakcompliance_ingestion_runs_status_check
    CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'));
