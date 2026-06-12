# Agents Guide — Break Compliance

Operational guide for AI agents. Read this **and** `CLAUDE.md` before changing code.
The hard rules below are non-negotiable.

## Mission

Read-only break-compliance reporter. Reviews whether Clockify users took the breaks
their workspace policy requires. **Never** creates/edits time entries, **never** sends
messages, **never** writes anything to Clockify. Fail-closed on auth, fail-loud on
misparse.

## Before changing code

1. **`CLAUDE.md`** — settings model, deploy info, hard rules.
2. **`docs/api-calls.md`** — outbound + inbound API shapes with live-probe evidence.
3. **`docs/clockify-marketplace/`** — canonical marketplace docs mirror; cite paths in
   commit messages when adding new functionality.
4. **`docs/addon-java-sdk/`** — Java SDK 1.5.3 source; the SDK already verifies — never
   reimplement.

## Run + verify

```sh
# Full suite (JDK 21 required; system JDK 25 breaks Lombok).
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test
# Expect 304 green. Postgres + Redis spin up via Testcontainers.
# Colima users add: DOCKER_HOST=unix:///Users/<you>/.colima/default/docker.sock

# Targeted run.
mvn -B -ntp test -Dtest='LifecycleControllerTest,BreakRuleEngineTest'

# Deploy (push to main does NOT auto-deploy).
railway up --service BreakCompliance --ci

# Tail logs during smoke-test.
railway logs --service BreakCompliance
```

## Probing live Clockify (dev workspace)

API key + workspace id in `/tmp/clockify-livetest.env` — never copy into the repo.

```sh
set -a; source /tmp/clockify-livetest.env; set +a

curl -s -H "X-Api-Key: $CLOCKIFY_API_KEY" \
  https://developer.clockify.me/api/v1/user | jq .

# Detailed report (matches the addon's outbound shape).
curl -s -X POST \
  -H "X-Api-Key: $CLOCKIFY_API_KEY" -H "Content-Type: application/json" \
  https://developer.clockify.me/report/v1/workspaces/$CLOCKIFY_WORKSPACE_ID/reports/detailed \
  -d '{"dateRangeStart":"2026-05-04T00:00:00","dateRangeEnd":"2026-05-17T23:59:59","detailedFilter":{"page":1,"pageSize":50}}' \
  | jq '.timeentries | length'
```

Probe-lab fixtures + findings at `/Users/15x/Downloads/WORKING/clockify-api-probe-lab/`
— refer to its `findings/SUMMARY.md` and `ATTENDANCEANDTIMEREPORTS.md` before debugging
any API call shape.

## Hard rules (don't break these)

| Rule | Why |
|---|---|
| Read `backendUrl`/`reportsUrl` from JWT claims — never hardcode. | Dev portal uses `/report/v1/…`; production `reports.api.clockify.me/v1/…`. JWT carries the env-correct URL. |
| `X-Addon-Token` header (not `Authorization`) for outbound Clockify. | Clockify rejects `Authorization`. |
| Settings = native structured-settings only. No `/settings` iframe. | Per `docs/clockify-marketplace/build/manifest/structured-settings.md`. |
| `SETTINGS_UPDATED` is the canonical wrapper `{workspaceId, addonId, settings: [{id,value},…]}` — confirmed by 2026-05-11 live probe. | `SettingsUpdatedPayload.extractUpdates` also accepts the legacy bare-array + defensive single `{id,value}` shape. Unknown shapes drift-log + return 200. |
| Detailed-report response key is `timeentries` (ALL LOWERCASE). | Spec mislabels as `timeEntries`. Live API returns lowercase. |
| Body dates are `yyyy-MM-dd'T'HH:mm:ss` (no `Z` suffix). | Server interprets in user timezone. |
| `type=TIME_OFF`/`HOLIDAY` entries → `EntryClassifier.Kind.IGNORED` (§25/§29). | Engine skips only those entries, splits the continuous-work chain, blocks gap synthesis across them, and still evaluates same-day WORK. |
| `/api/*` is `X-Addon-Token`-header-only. `/sidebar` accepts `?auth_token=` once, then JS scrubs it. | Lifecycle/webhook auth fail-closed via `AddonTokenAuthFilter` + `WebhookAuthFilter`. |
| `INACTIVE` installations cannot reach Clockify. | `IngestionService` throws `InstallationInactiveException` → 503 `installation_inactive` banner. |
| Webhook idempotency = Redis SETNX with ≥ 24h TTL. | Clockify retries up to ~24h. |
| Flyway migrations are additive only. | DB shared across deploys; drops break rollback. Use `V<n>__add_*.sql`. |
| Production `INSTALLATION_TOKEN_KEY` must be 64 hex chars **and not** legacy `…aa` or all-zero. | `CryptoConfig.validateActiveKey` fail-fasts at startup. |
| JDBC URL keeps `sslmode=require` + `tcpKeepAlive=true`. `PG_SSLMODE` is an emergency env-knob, not a default to flip. | Railway drops idle TCP; without keepalive Hikari hands out half-dead sockets and the first query fails opaquely. |
| Logger levels for `me.apet97.breakcompliance` come from `LOG_LEVEL_APP` (`application.yaml`). Don't hardcode `level="DEBUG"` in `logback-spring.xml`. | Production runs INFO by default; flip per-incident with `railway variables --set LOG_LEVEL_APP=DEBUG` (no redeploy). |
| `spring.jpa.open-in-view: false` — touch lazy-loaded relations only inside `@Transactional`. | The session closes at the service boundary; controller-layer lazy access throws `LazyInitializationException`. |
| HikariCP `leak-detection-threshold: 20000` is on. If you see `Connection leak detection triggered` in logs, fix the leak (forgotten session / unclosed `EntityManager`). | The pool is 10 connections; one leak starves the app under multi-tenant load. |

## Settings model (current)

Split surface: native structured-settings owns ten admin-only fields for per-threshold
fine-tuning; sidebar owns the preset chooser. The dropdown was removed from the manifest
because Clockify's native UI renders each field independently and never re-fetches
siblings on change — so backend-driven cross-field writes (the previous "preset-as-loader"
pattern) weren't visible without a page reload. All eleven values still land on
`WorkspaceSettings.customXxx` columns (the `custom_` prefix is historical — the
`customPolicyEnabled` flag no longer gates evaluation; always-on).

Preset selection: sidebar → `POST /api/presets/apply {presetKey}` →
`InstallationService.applyPreset(workspaceId, presetKey)` overwrites all 8 threshold
columns from `RuleTemplatePresets.{key}.toEntity(…)`, sets `appliedPresetKey`, re-runs
`SettingsWarning.validate(...)`, and saves in one transaction. The lifecycle handler's
defensive `appliedPresetKey` parser stays so any cached SETTINGS_UPDATED delivery still
round-trips (Clockify won't push it post-manifest-removal, but the receiver is tolerant).

The engine uses `synthesizeWorkspaceTemplate(input)` to wrap `WorkspaceSettings` into a
transient `RuleTemplate`. Per-user template resolution (`RuleTemplate` +
`TemplateAssignment` tables) is dead code in evaluation; the tables remain for
back-compat only.

When `fallbackDetectionEnabled=true` the engine adds a **gap-as-break** pass inside
`BreakRuleEngine.evaluateSegments`: a wall-clock gap of `[minBreakSegmentMinutes, 120]`
minutes between two consecutive WORK entries on the same day is credited as a
synthesised qualifying break (counts toward `breakMinutes`, resets the
continuous-work run, feeds `longestQualifyingBreakMinutes`, reported on findings as
`evidence.syntheticBreakMinutes`). `IGNORED` (TIME_OFF/HOLIDAY) and explicit `BREAK`
entries break the prev-work chain so no synthesis spans them. The 120-min ceiling is a
hardcoded private constant (`MAX_GAP_AS_BREAK_MINUTES`) — gaps above that are treated
as a new shift, not a break. Sidebar renders `Break: 30m · 30m detected` only when
synthetic > 0. `IGNORED` entries are not credited as break minutes.

## Don'ts

- **No deep-link "open settings page" from the iframe.** Clockify's `navigate`
  postMessage only supports `{"type":"tracker"}` (see
  `docs/clockify-marketplace/build/window-events.md`). The active-template chip,
  the **Switch…** button (sidebar-side preset chooser), and the collapsible "where do I
  fine-tune" hint are the documented affordances.
- **No new-window launch for the native settings page.** Dev portal uses a catalog addon-id
  we don't have from JWT claims (`claims.addonId` is the per-workspace installation id).
- **Preset selection lives in the sidebar.** Don't re-add `appliedPresetKey` to the
  manifest — the field was removed because Clockify can't surface a backend-driven
  cross-field write without a page reload. The defensive lifecycle parser stays for
  legacy deliveries, but new code paths must go through `POST /api/presets/apply`.
- **No new iframe controls for threshold fine-tuning.** Individual fields stay native
  so admins land on Clockify's familiar settings chrome. The sidebar carve-out is the
  preset chooser only.
- **No `RuleTemplate` lookups in new code paths.** Engine ignores them.
- **Don't drop `Last-Page` header parsing** if you add paginated calls. We currently
  also approximate with `entries.size() < PAGE_SIZE` (documented in
  `docs/api-calls.md`).
- **No outbound from `INACTIVE` installations.** `IngestionService` is the single guard;
  new outbound paths must consult `Installation.status` before reading the token.
- **No `_WRITE` scopes, ever.** The mission is read-only. Adding any scope that
  lets the addon mutate workspace state breaks the marketplace listing
  commitment in `docs/PRIVACY.md`.
- **Read-only fetches that close documented false-positive gaps are OK.**
  The Detailed Report is the source of truth for break evaluation. Three
  supplementary read calls — `GET /v1/workspaces/{ws}/holidays` (P1.1),
  `POST /v1/workspaces/{ws}/time-off/requests` (P1.2),
  `GET /v1/workspaces/{ws}/users` (P2.3) — exist because workspaces that
  don't auto-create `type=HOLIDAY` / `type=TIME_OFF` time entries would
  otherwise produce false-positive findings, and stale `userName`
  columns make findings unreadable after a rename. Shape verified live
  on 2026-05-13 against the sacrificial workspace (`docs/api-calls.md`
  §1a / §1b / §1c). Don't add a *fourth* read endpoint without the same
  justification: documented false-positive class + live-probed shape +
  added to `docs/api-calls.md`.
- **Don't tune Hikari by raising `maximum-pool-size` alone.** The 3-phase
  ingestion split exists so the long Clockify HTTP call doesn't hold a DB
  connection. If the pool gets saturated, look for a missed split first.
- **Don't add Redis calls in hot paths without a fail-closed contract.** Today
  `WebhookIdempotencyStore.markSeen` and `ClockifyRateLimiter.acquire` let
  Redis exceptions bubble — that's intentional (Clockify retries the webhook,
  ingestion aborts). New Redis-backed safety checks should preserve that
  semantic.

## When you change behavior

1. Update tests (`src/test/java/me/apet97/breakcompliance/...`).
2. Update `CLAUDE.md` if the settings model, hard rules, or build steps change.
3. Update `docs/api-calls.md` if any outbound or inbound API shape changes.
4. Commit message format: `type(scope): short summary` (matching `fix(reports): …`,
   `feat(custom-policy): …`, `refactor(settings): …`).
5. `mvn test` green BEFORE `git push`.

## Numbered commit refs (archaeology)

- **§1–§9** — initial takeover (contract fixes, de-minify sidebar, seed templates,
  settings persistence, custom policy, 401 graceful handling, CDN styling).
- **§10** — ArbZG typo + preset reorder.
- **§11** — webhook idempotency confirmed.
- **§12** — iat replay protection.
- **§13** — payload-drift logger.
- **§14** — 429 Retry-After parsing + retry cap.
- **§15** — verify.
- **§16** — `/v1/` path + ISO dates + response key (`f7db0e6` reverted the camelCase
  mistake — live API returns `timeentries` lowercase).
- **§17** — 9 granular custom policy fields.
- **§18** — single-tab redesign, preset-as-loader, engine-from-`WorkspaceSettings`.
- **§19/§20** — deferred (diagnostic logging, in-sidebar settings panel).
- **§21** — userName captured; dropdown removed; Settings button later reverted.
- **§22** — Settings button removed entirely, static caption added.
- **§23** — security hardening, SDK conformity audit, test-suite verification.
- **§24** — launch-readiness: SETTINGS_UPDATED canonical object wrapper accepted
  (`SettingsUpdatedPayload`), sidebar UI/UX (active-template chip + thresholds popover,
  "Last checked" relative timestamp, refresh button, empty-state polish, theme-flicker
  fix, dark-mode WCAG-AA, narrow-viewport responsive, full a11y), designed 64×64 icon,
  real support email.
- **§25** — quality/perf/UX audit pass: `EntryClassifier` treats TIME_OFF/HOLIDAY as
  `IGNORED` (fixes false-positive findings on PTO/holiday days), shared `RestClient`
  bean (no more per-call `RestClient.create()`), V7 composite indexes on hot paths,
  pivot shows every day in range, `visibilitychange`-aware "Last checked" ticker,
  popover overflow fix, focus-on-Esc, `prefers-reduced-motion`, screen-reader labels,
  4 new service tests (`WorkspaceDataDeletion`, `RateLimiter`, `IdempotencyStore`,
  `RefreshSignal`) + `EntryClassifierTest`. 226 tests green.
- **§26** — UX pass after second review round: human-readable preset + timezone
  manifest labels (sidesteps the `allowedValues: List<String>` SDK limit), Title-Cased
  preset names, `.required(true)` on dropdowns to drop Clockify's auto-injected "None",
  `.placeholder("0 = disabled")` on the second-tier numeric fields. UTF-8 charset
  forced on `/manifest` (rescues `§` from mojibake). Cross-field validation
  (`SettingsWarning`) persisted on `workspace_settings.validation_warnings` (V9, additive)
  and surfaced in a sidebar banner via `/api/session`. Async ingest: bounded
  `ingestExecutor` (AsyncConfig), `POST /api/ingest/detailed-report` returns 202 with
  the run id; sidebar polls `GET /api/ingest/runs/{id}` with exp-backoff + Cancel link.
  Preset chooser relocated to the sidebar — new `GET /api/presets` + admin-gated
  `POST /api/presets/apply`, inline preview cards, Matches/Customized pill, confirm
  before overwrite. **255 tests green** (+`PresetControllerTest`,
  `IngestRunControllerTest`, `SettingsWarningTest`, rewritten `IngestionControllerTest`
  with a `SyncTaskExecutor` override for in-test async).
- **§27** — Marketplace readiness (P0 + P1 + active consumer + live validation;
  plan at `~/.claude/plans/verdict-do-not-zesty-gray.md`). Commits
  `206e099..1257ffd` on PR #1. Highlights:
  - **P0**: `DetailedReportFetcher` accepts `timeentries` AND `timeEntries` as a
    defensive fallback; `WORKSPACE_READ` scope dropped (unused); stale-`DELETED`
    guard in `InstallationService.handleDeleted` compares JWT `iat` to the
    stored `installedAt` (30s grace).
  - **Active webhook consumer**: `RefreshSignalConsumer` (`@Scheduled
    fixedDelay=30s`, debounce=20s) drains PENDING signals, groups by
    workspace, computes covering window from `dateHint`, dedupes against
    in-flight `IngestionRun`, dispatches via
    `IngestionService.beginAsyncForRefresh(…, Consumer<runId>)`. V10
    migration extends `refresh_signals.status` CHECK with CLAIMED /
    CONSUMED / FAILED / COALESCED + adds `ingestion_run_id` back-pointer.
  - **P1 hardening**: `IngestionRunReaper` (`@Scheduled`) marks runs stuck
    in RUNNING past 10 min as FAILED + releases their CLAIMED signals;
    `IngestionService.prepareRun` throws `IngestionRunInProgressException`
    (→ 409 with `existingRunId`) when a RUNNING run for the same
    workspace+range exists; Prometheus metrics via
    `micrometer-registry-prometheus` (`/actuator/prometheus` emits
    `breakcompliance_webhook_received{event}` /
    `_refresh_signals_processed{outcome}` /
    `_ingest_run_duration` / `_ingest_entries_processed` /
    `_ingest_run_failed{reason}`); HSTS only set when
    `request.isSecure()` (Railway-aware via `X-Forwarded-Proto`).
  - **CI**: Dependabot weekly + CodeQL Java analysis.
  - **Build**: Testcontainers bumped to 1.20.4 + surefire system property
    `api.version=1.44` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` so the
    suite runs identically under Docker Desktop and Colima.
  - **Live validation**: production install/uninstall captured in
    `docs/LIVE_VALIDATION.md` — three webhook-driven ingest cycles
    (entries=33→34→35), `/actuator/prometheus` emitting real values,
    12/12 workspace tables at zero rows post-uninstall.
  - **Marketplace packet**: new `docs/LISTING.md`, `docs/SUPPORT.md`,
    `CHANGELOG.md`; refreshed `docs/PRIVACY.md`, `docs/SECURITY.md`,
    `docs/DATA_RETENTION.md`.
  - **Test count**: 279 green (+`RefreshSignalConsumerTest`,
    `IngestionRunReaperTest`, +1 stale-DELETED case in
    `LifecycleControllerTest`, +1 dedupe case in `IngestionControllerTest`,
    +5 iat extraction cases in `ClaimsNormalizerTest`, parser fallback
    cases in `DetailedReportFetcherTest`, dateHint case in
    `RefreshSignalServiceTest`; `ClockifyRateLimiterTest.overBudget…`
    de-flaked with a bucket-boundary alignment).
- **§28** — P2 product polish (0.2.0; plan at
  `~/.claude/plans/all-you-re-picking-up-zesty-moth.md`). Six commits
  on main:
  - **Non-admin sidebar gating**: Check Compliance / Refresh /
    Switch-preset disabled with "Workspace admin required" tooltip
    when `state.session.workspaceRole` isn't ADMIN/OWNER; no 403
    round-trips. Diverged customized-pill drops its Reset affordance
    for non-admins.
  - **Staleness indicators**: new `GET /api/ingest/runs/latest` (204
    when no COMPLETED run) seeds `state.lastRunAt` on sidebar load;
    amber "Pending refresh · webhook Xm ago" pill rendered when any
    PENDING/CLAIMED `refresh_signals` row has `receivedAt` newer
    than the latest completed run's `completedAt`. Repository gains
    `findFirstByWorkspaceIdAndStatusOrderByCompletedAtDesc`.
  - **CSV export**: new `GET /api/findings/export?format=csv` —
    RFC 4180 attachment, columns `date,userId,userName,severity,
    code,message,workMinutes,breakMinutes,syntheticBreakMinutes,
    templateId,createdAt`. Sidebar download via blob + a tag with
    `download` attribute.
  - **Finding review UX**: new admin-gated
    `POST /api/findings/{id}/review` upserts
    `breakcompliance_finding_reviews` (table + enum already shipped
    in V1; controller was inert). `GET /api/findings` now embeds
    `review: {status, note, updatedAt} | null` per row. Sidebar
    Checklist cycles a finding through OPEN → ACK → OVERRIDE via
    per-row button with optional `window.prompt` audit note.
    `FindingsService.exists(workspaceId, findingId)` is the
    workspace-scoped guard.
  - **Locale-aware date labels**: `NormalizedClaims.userTimeZone`
    (canonical claim + legacy `userTimezone` / `tz` aliases) flows
    to the sidebar; pivot weekday + M/D labels rendered via
    `Intl.DateTimeFormat` (anchored at 12:00 UTC to avoid DST
    midnight shifts).
  - **Docs**: `docs/WHAT_COUNTS_AS_A_BREAK.md` — admin-facing
    explainer of WORK/BREAK/IGNORED classification, the
    gap-as-break heuristic, the `minBreakSegmentMinutes` floor,
    and the hardcoded `MAX_GAP_AS_BREAK_MINUTES = 120` ceiling.
  - **Test count**: 296 green (+3 `IngestRunControllerTest`,
    +5 `FindingsControllerTest` review cases, +4
    `FindingsControllerTest` CSV cases, +3 `ClaimsNormalizerTest`
    userTimeZone, +2 `SessionControllerTest` userTimeZone).
- **§29** — Marketplace submission hardening (this branch): Maven project version
  aligned to `0.2.0`; sidebar/session settings deep links removed in favor of the
  documented breadcrumb only; `TIME_OFF`/`HOLIDAY` ignored-entry semantics clarified
  and pinned with tests; V15 retires any pre-existing duplicate RUNNING rows and
  releases their CLAIMED signals before installing a partial unique index preventing
  duplicate RUNNING ingests under concurrent starts; detailed-report pagination /
  live-shape fixture tests added; operations and submission checklist docs added.
  304 tests green on 2026-06-12.
