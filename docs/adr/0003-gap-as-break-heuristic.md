# ADR 0003: Gap-As-Break Heuristic

## Status

Accepted

## Context

Some workspaces record breaks by stopping the timer instead of logging explicit
`BREAK` entries. Without a heuristic, those workspaces get false positives even
when users took a real break.

## Decision

When `fallbackDetectionEnabled=true`, the engine credits a wall-clock gap of
`[minBreakSegmentMinutes, 120]` minutes between consecutive same-day `WORK`
entries as a synthesized qualifying break.

## Consequences

The synthetic break contributes to break minutes, resets continuous-work runs,
and is exposed as `evidence.syntheticBreakMinutes`. Gaps over 120 minutes are
treated as a new shift rather than a break.
