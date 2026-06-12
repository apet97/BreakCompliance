# ADR 0004: Suppression Caches

## Status

Accepted

## Context

Clockify Detailed Report can include `TIME_OFF` and `HOLIDAY` rows, but not every
workspace creates those entries. The engine needs holiday/time-off context
without adding broad repeated calls during evaluation.

## Decision

Ingestion refreshes add-on-owned suppression caches for holidays and approved
time off. Evaluation reads those caches and remains pure.

## Consequences

Suppression refresh is best-effort after successful ingest. Group-only holidays
are skipped with a warning until a dedicated group-membership expansion module
exists; they are not treated as workspace-wide.
