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
# Expect 255+ green. Postgres + Redis spin up via Testcontainers.

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
| `type=TIME_OFF`/`HOLIDAY` entries → `EntryClassifier.Kind.IGNORED` (§25). | Engine skips them; otherwise PTO/holiday days produce false-positive findings. |
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

## Don'ts

- **No deep-link "open settings page" from the iframe.** Clockify's `navigate`
  postMessage only supports `{"type":"tracker"}` (see
  `docs/clockify-marketplace/build/window-events.md`). The active-template chip,
  the **Switch…** button (sidebar-side preset chooser), and the collapsible "where do I
  fine-tune" hint are the documented affordances.
- **No `window.open` for the native settings page.** Dev portal uses a catalog addon-id
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
- **No new outbound scope without scrutiny.** The Detailed Report already supplies
  `userName`, `userEmail`, `type` (REGULAR/BREAK/HOLIDAY/TIME_OFF), and
  `timeInterval.timeZone`. Don't add `/v1/users`, `/v1/time-off`, or `/v1/holidays`
  endpoints — same data, +1 scope cost per call.
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
