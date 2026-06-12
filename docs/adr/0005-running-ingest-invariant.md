# ADR 0005: One Running Ingest Per Workspace Range

## Status

Accepted

## Context

Admins can double-click refresh, and webhooks can overlap manual refreshes. Two
concurrent ingests for the same workspace and date range waste Clockify quota
and can race when replacing findings.

## Decision

Only one RUNNING ingest may exist for `(workspaceId, dateRangeStart,
dateRangeEnd)`. `IngestionService.prepareRun` checks for an existing run, and
Flyway V15 enforces the invariant with a partial unique index.

## Consequences

Controllers return 409 with the existing run id instead of queueing duplicates.
The reaper can safely mark stuck runs failed and release claimed refresh signals.
