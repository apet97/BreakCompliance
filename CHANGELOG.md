# Changelog

All notable changes to **Break Compliance for Clockify** are recorded
here. The format is loosely [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`Unreleased` collects work merged to `main` but not yet published to
the Clockify marketplace. The first marketplace submission ships as
`0.1.0`.

## Unreleased

_No unreleased changes yet — anything between marketplace submissions
will land here first._

## 0.1.0 — 2026-05-13 (initial marketplace submission)

First public release. Production-validated against the Clockify dev
portal on 2026-05-13 — see `docs/LIVE_VALIDATION.md` for the evidence
packet.

### Added

- **Native structured-settings tab** with ten admin-only threshold
  fields (work threshold, required break, minimum segment, max
  continuous work, grace period, allow-split-breaks toggle, second-
  tier thresholds, timezone strategy, fallback-detection toggle).
- **Sidebar preset chooser** with preview + confirm flow and a
  "Customized — Reset to <preset>?" indicator next to the active
  preset.
- **Three starter presets**: Custom (basic), Germany (ArbZG §3 & §4),
  California (IWC Meal & Rest).
- **Deterministic rule engine** evaluating work minutes, explicit
  break entries, fallback gap-as-break heuristic, two-tier
  thresholds, split-break policy, max continuous work, and timezone
  bucketing.
- **Async ingest pipeline** (`POST /api/ingest/detailed-report` →
  202 + poll URL) so the long Clockify Detailed Report call never
  blocks the request thread.
- **Active webhook-driven refresh loop**: `RefreshSignalConsumer`
  polls `PENDING` signals past a configurable debounce window
  (default 20s), groups by workspace, dedupes against in-flight
  `IngestionRun` rows, and dispatches a single ingest+evaluate
  cycle per workspace+date window.
- **Stuck-run reaper**: `@Scheduled` job that marks runs stalled
  in `RUNNING` past a threshold (default 10 min) as `FAILED` with
  `errorCode=stuck_run_reaped` and releases their `CLAIMED` refresh
  signals back to `PENDING`.
- **Controller-layer ingest dedupe**: an in-flight run for the same
  `(workspaceId, dateRange)` makes both `IngestionController` and
  `RefreshSignalsController` return `409 Conflict` with the existing
  run id instead of spawning a duplicate.
- **Stale-`DELETED` guard**: lifecycle handler compares JWT `iat` to
  the stored `installedAt` (30s grace for clock skew + 1-second JWT
  precision) so a 24-hour-old DELETED retry can't wipe a fresh
  reinstall.
- **Detailed Report parser** accepts both `timeentries` (live API)
  and `timeEntries` (OpenAPI spec) keys; the live shape always wins
  but a future Clockify migration can't silently zero out ingests.
- **Marketplace-required security headers** on every response
  (CSP with `frame-ancestors https://*.clockify.me`, HSTS,
  `X-Content-Type-Options`, `Referrer-Policy: no-referrer`,
  `Permissions-Policy`). HSTS only emitted when the request is
  HTTPS via Railway's `X-Forwarded-Proto`.
- **AES-GCM-256 token codec** for installation + webhook auth
  tokens, with key-id-aware rotation support.
- **Prometheus metrics** at `/actuator/prometheus`:
  `breakcompliance_webhook_received{event}`,
  `…_webhook_duplicate{event}`,
  `…_refresh_signals_processed{outcome}`,
  `…_ingest_run_duration` (Timer),
  `…_ingest_entries_processed`,
  `…_ingest_run_failed{reason}`.
- **CI**: Dependabot weekly Maven + GitHub Actions update PRs,
  CodeQL Java analysis on every PR and a weekly cron.
- **Marketplace evidence packet** under `docs/`: PRIVACY,
  SECURITY, DATA_RETENTION, LEGAL_NOTICES, LIVE_VALIDATION,
  LISTING, SUPPORT.

### Removed

- **`WORKSPACE_READ` scope** — never consumed anywhere in the
  codebase; dropped to keep the install-consent dialog honest. The
  add-on now requests exactly `TIME_ENTRY_READ`, `USER_READ`,
  `REPORTS_READ`.

### Tests

- 279 unit + integration tests, all green on `mvn verify` against
  Postgres + Redis Testcontainers (Docker Desktop or Colima — see
  the build-tooling commit for the bumped Testcontainers 1.20.4 +
  surefire config for portable cross-runtime support).
