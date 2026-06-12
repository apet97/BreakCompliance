# Changelog

All notable changes to **Break Compliance for Clockify** are recorded
here. The format is loosely [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`Unreleased` collects work merged to `main` but not yet published to
the Clockify marketplace. The first marketplace submission ships as
`0.1.0`.

## Unreleased

### Changed

- Marketplace-readiness hardening for the v0.2.0 submission artifact:
  Maven project version now matches `0.2.0`, settings deep-link output
  was removed from the sidebar/session contract, PTO/holiday semantics
  are documented as entry-level skips, and the webhook refresh docs now
  describe the active consumer/reaper flow.

### Fixed

- `TIME_OFF` / `HOLIDAY` entries now split the continuous-work run
  without being credited as break minutes, preventing false max-
  continuous findings across ignored Clockify entries.
- Concurrent ingest starts for the same workspace/date range are now
  protected by a partial unique index in addition to the service-level
  pre-check.

## 0.2.0 — 2026-05-13 (P2 product polish)

Six-PR polish rollup landed on top of `0.1.0`. No marketplace re-submission
required — these are additive UX + admin-experience improvements wired
behind the same auth + workspace scopes that shipped in `0.1.0`.

### Added

- **Workspace-admin gating in the sidebar.** Non-admins now see Check
  Compliance, Refresh, and Switch-preset rendered disabled with a clear
  "Workspace admin required" tooltip instead of producing a 403
  round-trip and a generic "Session expired" banner. The diverged
  customized-pill drops its Reset affordance for non-admins.
  (Backend `RequestValidator.requireAdmin` behaviour unchanged.)
- **Refresh staleness indicators.** New `GET /api/ingest/runs/latest`
  returns the workspace's most recent COMPLETED ingestion run (or 204
  No Content). On sidebar load it seeds `state.lastRunAt` so a freshly
  opened sidebar reads "Last checked Xm ago" without requiring a
  Check Compliance click first. An amber "Pending refresh · webhook
  Xm ago" pill renders when any PENDING/CLAIMED refresh signal has
  `receivedAt` newer than the latest completed run.
- **CSV export of findings.** New `GET /api/findings/export?…&format=csv`
  returns RFC 4180 CSV with a `Content-Disposition: attachment` header.
  Columns: `date, userId, userName, severity, code, message,
  workMinutes, breakMinutes, syntheticBreakMinutes, templateId,
  createdAt`. Sidebar exposes the download as an "⬇ Export CSV"
  button next to the Pivot/Checklist view toggle, visible once
  findings are loaded.
- **Finding review UX.** New admin-gated `POST /api/findings/{id}/review`
  with body `{status, note?}` upserts the workspace-scoped review row
  (`OPEN`/`ACKNOWLEDGED`/`OVERRIDDEN`). `GET /api/findings` now embeds
  the review state inline as `review: {status, note, updatedAt} | null`.
  Sidebar checklist view cycles a finding through OPEN → ACK → OVERRIDE
  via a per-row button, prompts for an optional audit note when
  transitioning into a non-OPEN state, and fades reviewed rows.
- **Locale-aware sidebar date labels.** `NormalizedClaims` gains a
  nullable `userTimeZone` field extracted from the canonical claim and
  two legacy aliases (`userTimezone`, `tz`). The pivot table now uses
  `Intl.DateTimeFormat` keyed off that timezone (falling back to the
  browser default when null) to render both the weekday name and the
  M/D label — replacing the hardcoded English `["Sun","Mon",…]` array.
- **Docs — "What counts as a break"** (`docs/WHAT_COUNTS_AS_A_BREAK.md`):
  admin-facing explainer covering explicit BREAK entries, the opt-in
  gap-as-break heuristic, IGNORED `TIME_OFF`/`HOLIDAY` semantics, the
  `minBreakSegmentMinutes` floor, and the hardcoded
  `MAX_GAP_AS_BREAK_MINUTES = 120` ceiling with rationale.

### Internal

- 17 new tests across `IngestRunControllerTest`, `FindingsControllerTest`,
  `ClaimsNormalizerTest`, and `SessionControllerTest` (296 total).
- New repository method
  `IngestionRunRepository.findFirstByWorkspaceIdAndStatusOrderByCompletedAtDesc`.
- New service method `FindingsService.exists(workspaceId, findingId)` for
  workspace-scoped existence checks.

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
