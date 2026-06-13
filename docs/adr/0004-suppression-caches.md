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

Holiday rows remain date-level suppressions: workspace-wide holidays skip every
user bucket on that date, and per-user holiday assignments skip only the
matching user's bucket.

Approved time-off rows keep their exact `startAt`/`endAt` instants. Evaluation
converts overlapping rows into synthetic, non-persisted `TIME_OFF` entries
clipped to the requested UTC date range. This preserves partial-day precision:
PTO intervals are ignored, but same-day work outside the approved window still
evaluates.

## Consequences

Suppression refresh is best-effort after successful ingest. Group-only holidays
are skipped with a warning until a dedicated group-membership expansion module
exists; they are not treated as workspace-wide.

Synthetic time-off entries must never be persisted into
`breakcompliance_time_entries`; the Detailed Report remains the only source of
persisted time-entry rows.
