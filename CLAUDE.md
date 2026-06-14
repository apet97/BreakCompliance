# Break Compliance — Clockify Add-on

Java 21 / Spring Boot 4.1 marketplace add-on. Reviews whether users took required breaks.
Manifest key `break-compliance-jvm`. BASIC plan. **Read-only** scopes:
`TIME_ENTRY_READ`, `USER_READ`, `REPORTS_READ` (no `_WRITE`, ever — `WORKSPACE_READ`
was dropped in P0 commit `029b0da` as unused).

Fast domain context lives in `CONTEXT.md`; durable design decisions live in
`docs/adr/`.

## Live deploy

| | |
|---|---|
| Host | `https://breakcompliance-production.up.railway.app` |
| Manifest | `…/manifest` |
| Railway | project `break-compliance` · service `BreakCompliance` · env `production` |
| Deploy | `railway up --service BreakCompliance --ci` (push does **not** auto-deploy) |
| Logs | `railway logs --service BreakCompliance` |
| Java SDK | `com.cake.clockify:addon-sdk:1.5.3` vendored in `repo/` (no GitHub Packages PAT needed) |

## Build + test

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test

find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check

NODE_OPTIONS=--no-warnings node --test src/test/js/*.mjs
```

System Maven defaults to JDK 25 which breaks Lombok — JDK 21 required.
**368 tests expected** (§37 after MessageSource runtime repair).
Postgres + Redis come up via Testcontainers.
Surefire env in `pom.xml` provides `INSTALLATION_TOKEN_KEY` + `api.version=1.44` +
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` so the suite runs
identically under Docker Desktop and Colima — `DOCKER_HOST` is the only env var
operators set externally (Docker Desktop default works without it; Colima users
export `DOCKER_HOST=unix:///Users/.../.colima/default/docker.sock`).

Spring Boot 4 modularized several integrations. Keep `spring-boot-flyway` with
Flyway 12.8.x so migrations auto-apply, keep `spring-boot-jackson2` until the
Clockify SDK/adapters move off Jackson 2 `ObjectMapper`, and use the Boot 4 test
packages (`org.springframework.boot.webmvc.test.autoconfigure`,
`org.springframework.boot.data.jpa.test.autoconfigure`,
`org.springframework.boot.jdbc.test.autoconfigure`) plus Spring Framework's
`@MockitoBean` / `@MockitoSpyBean`.

## Repo hygiene + direct main push

Before a direct `main` push handoff:

- Review `git status --short --untracked-files=all`.
- Stage only intentional repo files. Local agent tooling (`.claude/`,
  `docs/superpowers/`) and OS junk (`.DS_Store`) are gitignored and must stay out
  of the repo; do not stage stale plan drafts or other local helper artifacts
  unless the operator explicitly asks.
- If the operator asks to remove untracked items, delete them explicitly and
  recheck that `git status --short --untracked-files=all` is clean before
  editing or committing.
- Prove fast-forward safety with `git fetch origin main` and
  `git merge-base --is-ancestor origin/main HEAD` before `git push origin main`.
- Final handoff should name the commit SHA, verification commands/results,
  push result, and any live/deploy proof intentionally skipped.

## Runtime config (Railway env vars)

Spring profile **not** activated in prod — `application.yaml` is the base. Local
dev runs with `-Dspring.profiles.active=dev` to layer `application-dev.yaml`.

| Var | Default | Notes |
|---|---|---|
| `PGHOST` / `PGPORT` / `PGDATABASE` / `PGUSER` / `PGPASSWORD` | — | Railway-linked Postgres service-reference vars. The JDBC URL is built from these. |
| `PG_SSLMODE` | `require` | Emergency knob. Set to `disable`/`prefer` only if Railway's managed Postgres temporarily can't negotiate SSL. |
| `REDISHOST` / `REDISPORT` / `REDISUSER` / `REDISPASSWORD` | — | Railway-linked Redis service-reference vars. Username defaults to **empty** (local no-auth Redis works). |
| `INSTALLATION_TOKEN_KEY` | — | 64 hex chars. `CryptoConfig.validateActiveKey` fail-fasts on legacy/zero keys. |
| `INSTALLATION_TOKEN_KEY_ID` | `default` | Active key id in the `crypto.keys` map. |
| `LOG_LEVEL_APP` | `INFO` | Flip to `DEBUG` for live diagnosis without redeploy. |
| `ADDON_BASE_URL` | `http://localhost:8080` | Used by the manifest builder. |
| `EXTRA_FRAME_ANCESTORS` | — | Extra CSP `frame-ancestors` for embedding tests. |
| `BREAKCOMPLIANCE_CLOCKIFY_ALLOW_LOCAL_BASE_URLS` / `breakcompliance.clockify.allow-local-base-urls` | `false` | Dev/test-only opt-in for `http://localhost` Clockify base URLs. Production rejects local/non-Clockify hosts. |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | — | Marketplace-evidence allowlist (see `CorsConfigTest`). |
| `ENABLE_HSTS` | `false` | Railway terminates TLS; only flip on if testing HSTS preload. |
| `SIDEBAR_TOKEN_MAX_IAT_AGE_SECONDS` | `1800` | iat-replay window for sidebar JWT. |
| `IAT_CLOCK_SKEW_SECONDS` | `60` | Tolerance applied around `iat`. |

The JDBC URL is `jdbc:postgresql://{PG…}/{db}?sslmode={PG_SSLMODE:require}&tcpKeepAlive=true`
— `tcpKeepAlive=true` is mandatory in prod (Railway drops idle TCP silently).

HikariCP: `maximum-pool-size: 10`, `leak-detection-threshold: 20000`,
`max-lifetime: 1500000`. If a `HikariPool-N - Connection leak detection
triggered` WARN shows up in Railway logs, **fix the leak** (a service forgot to
close a session) — do not just bump the threshold.

`spring.jpa.open-in-view: false`. Any code path that needs lazy-loaded entity
relations must run inside an `@Transactional` boundary; pulling lazy fields
from a controller method body will throw `LazyInitializationException`.

## Settings model — split surface: threshold fields native, preset chooser sidebar (§18 / §22 / §24 / §26)

**Native structured-settings tab "Break Compliance"** — 17 admin-only fields:
10 break-policy fields plus 7 operational/admin controls:

| Field | Type | Default |
|---|---|---|
| `workThresholdMinutes` | NUMBER | 240 |
| `breakThresholdMinutes` | NUMBER | 15 |
| `minBreakSegmentMinutes` | NUMBER | 5 |
| `maxContinuousWorkMinutes` | NUMBER | 240 |
| `gracePeriodMinutes` | NUMBER | 5 |
| `allowSplitBreaks` | CHECKBOX | true (OFF = California meal-rule) |
| `secondWorkThresholdMinutes` | NUMBER | 0 (disabled — placeholder "0 = disabled") |
| `secondBreakThresholdMinutes` | NUMBER | 0 (disabled — placeholder "0 = disabled") |
| `timezoneStrategy` | DROPDOWN required | `Use entry's local time zone` |
| `fallbackDetectionEnabled` | CHECKBOX | false (ON: 5–120 min gap between two consecutive WORK entries on the same day counts as a qualifying break — see Engine note below) |
| `exemptUserIds` | TXT | blank (served as a single-space schema sentinel; parsed as blank/null) |
| `refreshDebounceSeconds` | NUMBER | 0 (use default 20s; accepted range 5–300) |
| `excludeUnsubmittedEntries` | CHECKBOX | false |
| `severityOverrideMissingBreak` | DROPDOWN | `VIOLATION` |
| `severityOverrideInsufficientBreak` | DROPDOWN | `VIOLATION` |
| `severityOverrideMaxContinuous` | DROPDOWN | `VIOLATION` |
| `nightShiftAttribution` | DROPDOWN | `start-day` |

**Sidebar preset chooser** owns `appliedPresetKey` (entity column unchanged).
Reason: Clockify's native settings UI renders each field independently and
never re-fetches sibling fields after a change, so a "pick preset → thresholds
populate" interaction in the native tab requires a full page reload to show
any effect — it confuses users into thinking the dropdown is broken. The
sidebar exposes the chooser via `POST /api/presets/apply` with a real preview
of each preset's values, a confirmation when the apply would overwrite custom
edits, and a "Matches preset / Customized — Reset to <preset>?" indicator.

**Handler** (`InstallationService.handleSettingsUpdated`) still tolerates a
legacy `appliedPresetKey` entry in inbound SETTINGS_UPDATED (label or slug)
as a defensive parser — Clockify won't push it now that the field is gone
from the manifest, but cached installs might.

**Engine**: `BreakRuleEngine.synthesizeWorkspaceTemplate(input)` wraps
`WorkspaceSettings` into a transient `RuleTemplate` per evaluation. No per-user
template resolution; the `breakcompliance_rule_templates` +
`breakcompliance_template_assignments` tables are engine-irrelevant (kept
additively).

Finding messages are generated through Spring `MessageSource` from
`messages.properties` (root fallback, required for Boot auto-configuration) and
`messages_en.properties` (English locale, only locale for now). The persisted
`message` column and CSV `message` output stay unchanged; future locale work
must not change finding codes, severity, evidence shape, or API/CSV columns.

**Gap-as-break heuristic** (`fallbackDetectionEnabled=true`,
`BreakRuleEngine.evaluateSegments`): when two consecutive `WORK`-classified
entries on the same day have a wall-clock gap of
`[minBreakSegmentMinutes, MAX_GAP_AS_BREAK_MINUTES=120]` minutes, the gap is
credited as a synthesised qualifying break — counts toward `breakMinutes`,
resets the continuous-work run, feeds `longestQualifyingBreakMinutes` (so the
California split-break rule still works), and is reported as
`evidence.syntheticBreakMinutes` on findings. The sidebar surfaces this as
`Break: 30m · 30m detected` only when synthetic > 0. `IGNORED` (TIME_OFF /
HOLIDAY) entries close the current continuous-work run, clear the prev-work
marker, and do not add break minutes; explicit `BREAK` entries reset the run
only when they meet the minimum segment floor. Designed for workspaces that
record breaks by stopping the timer rather than logging dedicated BREAK entries
— has no effect when every entry already carries a canonical `type`.

**Sidebar** shows the active preset as a **clickable chip** with a thresholds
popover, a **Matches preset / Customized — Reset?** pill next to it, and a
**Switch…** button that opens the preset chooser. Fine-tune individual fields
at: **Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings**.

**Sidebar findings views (§39 triage-first redesign).** The results toolbar
toggles three views; **Triage** is the default. Triage is a triage-first surface
built from the bundled design system (`.claude/.skills/Break Compliance Design
System/`): honest summary KPIs (**Open** with fail/warn split · **People
affected** · **Reviewed n/total** — no compliance %, since the backend only
persists problem days, so there is no compliant-day denominator), a prioritized
**Needs attention** feed of design-system FindingCards with inline
Acknowledge/Override, and a risk-sorted **People with findings** roster whose
rows filter the feed. **Pivot** and **Checklist** are unchanged alternate detail
views. Implementation is vanilla JS (no bundler, CSP `script-src 'self'` intact):
DS tokens were added to `sidebar/css/base.css`, the `bc-*` component CSS lives in
`sidebar/css/triage.css`, pure derivations in `sidebar/triage-metrics.js` (covered
by `src/test/js/triage-metrics.test.mjs`), and the views render in
`sidebar.js#renderTriage`. Finding short-labels (`finding.rule.*`) are
presentation-only — persisted codes/messages/severity/CSV are untouched.

## Outbound Clockify calls

Four read-only calls: the Detailed Report source of truth plus the three
suppression/directory refreshes documented in `docs/api-calls.md`. Do not add
another outbound endpoint without a documented false-positive class and a
live-probed shape.

## Hard rules (don't break these)

| Rule | Why |
|---|---|
| Read `backendUrl`/`reportsUrl` from JWT claims — never hardcode. | Dev portal uses `/report/v1/…`; production `reports.api.clockify.me/v1/…`. JWT has the env-correct URL. |
| Production Clockify base URLs must be HTTPS `*.clockify.me`; local HTTP URLs require the explicit dev/test opt-in property. | Prevents a tampered JWT from steering outbound calls to localhost or arbitrary hosts. |
| `X-Addon-Token` header (not `Authorization`) for outbound. | Clockify rejects `Authorization`. |
| `/manifest` must advertise `schemaVersion: "1.5"` when serving structured settings. | The Java SDK 1.5.3 builders stop before schema 1.5, so `ManifestController` normalizes the served JSON. Clockify's dev portal rejects object settings under older schema validation. |
| Native TXT setting defaults must be at least one character. | Clockify schema 1.5 rejects empty string `value`; `exemptUserIds` uses a single-space sentinel and `InstallationService` already maps blank strings to null. |
| Threshold fields = native structured-settings; preset selection = sidebar. | Clockify renders each native field independently and never re-fetches siblings after a change, so a backend-driven cross-field write isn't visible until reload. Sidebar lets us preview, confirm, and apply atomically. |
| `SETTINGS_UPDATED` is the canonical wrapper `{workspaceId, addonId, settings: [{id,value},…]}` (§24). | `SettingsUpdatedPayload.extractUpdates` also accepts the legacy bare-array + defensive single-object shapes; unknown shapes drift-log + 200. |
| Detailed-report response key is `timeentries` (ALL LOWERCASE) — parser ALSO accepts `timeEntries` as a defensive fallback and throws when the body is blank/null or neither key is an array. | Live API returns lowercase; the camelCase fallback (P0 commit `029b0da`) is belt-and-suspenders in case Clockify ever migrates the wire format to match its own spec. Missing/invalid entry arrays and blank bodies must fail loud, not look like an empty workspace. |
| `lifecycle.deleted` is guarded by `iat < installedAt - 30s` rejection (P0 commit `029b0da`). | Clockify retries up to ~24h; a stale DELETED arriving after a reinstall would otherwise wipe the fresh install. |
| `IngestionService.prepareRun` throws `IngestionRunInProgressException` (→ controller 409) if a RUNNING run for the same `(workspaceId, dateRange)` exists. V15 also enforces this with a partial unique index, retires pre-existing duplicate RUNNING rows, and releases CLAIMED signals attached to retired duplicates. | Admin double-click "Refresh" or webhook+admin overlap can't queue duplicate ingests, even under DB-level concurrency. |
| `IngestionRun.status=COMPLETED` is written only after detailed-report entries are persisted and holiday, time-off, and user-directory refresh attempts return. | Sidebar evaluation and refresh-signal callbacks must not observe a completed run while suppression data is still stale; best-effort suppression failures still complete, and one supplemental failure must not skip the others. |
| Refresh-signal consumer state machine: `PENDING → CLAIMED → CONSUMED` (or `COALESCED` / `FAILED`). | The webhook handler records `PENDING` and returns 204; the `@Scheduled` consumer drains the queue after `debounce-ms` (default 20s), dedupes against in-flight runs, dispatches via `beginAsyncForRefresh`, and `IngestionRunReaper` releases stale CLAIMED signals. |
| Dates in detailed-report body are `yyyy-MM-dd'T'HH:mm:ss` (no `Z`). | Server interprets in user timezone. |
| `type=TIME_OFF` / `type=HOLIDAY` entries are `IGNORED` by the engine (§25/§29). | They skip only their own duration, split the continuous-work chain, and block gap synthesis across PTO/holiday windows; mixed days still evaluate real WORK entries. |
| Cached approved time-off rows become synthetic, non-persisted `TIME_OFF` entries for evaluation. | Partial-day PTO must not suppress a whole user-day; same-day WORK outside the approved interval still evaluates. |
| `/api/*` is header-token-only. `/sidebar` accepts `?auth_token=` once, then JS scrubs it. | Lifecycle/webhook/api filters all fail-closed. |
| CSP uses `script-src 'self'`; `/sidebar` must not render inline scripts. | Theme bootstrap lives in `/theme-init.js`, loaded before CSS. Add/keep the no-inline sidebar contract test for any HTML shell change. |
| `INACTIVE` installations cannot reach Clockify. | `IngestionService` throws `InstallationInactiveException` → 503 `installation_inactive`. |
| Webhook idempotency = Redis SETNX, TTL ≥ 24h. | Clockify retries up to ~24h. |
| Flyway migrations are additive only. | DB shared across deploys; column drops break rollbacks. |
| Production `INSTALLATION_TOKEN_KEY` must be 64 hex chars and not legacy `…aa` or all-zero. | `CryptoConfig.validateActiveKey` fail-fasts at startup. |
| Don't hardcode `level="DEBUG"` in `logback-spring.xml`. Logger levels live in `application.yaml` via `${LOG_LEVEL_APP:INFO}`. | Otherwise prod runs at DEBUG and the redaction regex has to keep up with every new log line — log-leak risk. |
| Don't strip `sslmode` / `tcpKeepAlive` from the JDBC URL. | Without keepalive, Railway's idle-TCP cutoff makes Hikari hand out half-dead sockets and the first query fails opaquely. |
| Don't disable Hikari `leak-detection-threshold`. | It surfaces forgotten sessions; under load the pool is only 10 connections and a single leak starves the whole app. |

## Known Clockify-renderer UI limitations

These are NOT bugs in this codebase — file feedback upstream with Clockify if they ever block a launch goal.

| Limitation | Workaround in place |
|---|---|
| Native structured-settings `allowedValues` only accepts `List<String>` — no key/label pairs. | The dropdown values for `appliedPresetKey` and `timezoneStrategy` ARE the user-visible labels (e.g. `"California (IWC meal/rest)"`). The lifecycle handler maps inbound labels back to internal slugs via `RuleTemplatePresets.fromManifestLabel` / `TimezoneStrategy.fromManifestLabel`. Don't introduce a parallel raw-key set without also updating those mappers. |
| `navigate` postMessage only supports `tracker` — no deep-link to the addon's own settings page from the iframe. | Sidebar shows a collapsible "Where do I configure thresholds?" hint pointing admins at the breadcrumb in Clockify's own UI. |
| Checkbox fields render the label twice (once as field name, once next to the input). | Cosmetic only — we own the field name copy; the duplicate is Clockify's renderer adding its own. Don't try to defeat it with empty `name`. |
| `.description()` rendering on individual fields is at Clockify's discretion. | We still emit descriptions on every field — they're free, marketplace reviewers read them, and the strings are reusable on any future surface. |

## Key source files

```
src/main/java/me/apet97/breakcompliance/
  addon/
    auth/         JWT verify + claims normalisation + lifecycle/webhook filters
    lifecycle/    INSTALLED/DELETED/SETTINGS_UPDATED/STATUS_CHANGED
                  + SettingsUpdatedPayload (object/array/single shape parser)
                  + WorkspaceDataDeletionService (DELETED wipe)
                  + PayloadDriftLogger (one-shot WARN on unknown keys)
    manifest/     ManifestController (Gson serialises the SDK manifest and
                  pins served schemaVersion to 1.5 for structured settings)
    ui/           SidebarHtmlController serves the iframe HTML shell
    webhook/      NEW/UPDATED/DELETED time-entry + Redis SETNX (24h TTL) + RefreshSignalService
                  (records PENDING signals with dateHint from timeInterval.start) +
                  RefreshSignalConsumer (@Scheduled drain → debounce → dedupe → dispatch).
  api/            Session, Findings, Ingestion, IngestRun, Presets, RefreshSignals
                  controllers + AddonTokenAuthFilter + InstallationInactiveException +
                  IngestionRunInProgressException + IngestionRunReaper (@Scheduled
                  stuck-run recovery).
                  IngestionService runs prepare/fetch/persist, then refreshes suppression
                  cache before marking the run COMPLETED. Dispatched async via
                  ingestExecutor; sidebar polls /api/ingest/runs/{id} for status.
                  beginAsyncForRefresh(workspaceId, from, to, reportsUrl, Consumer<runId>)
                  is the consumer-callable variant — callback receives the runId only
                  after executeRun returns, so CLAIMED signals are marked CONSUMED after
                  suppression refresh has attempted.
                  PresetController serves GET /api/presets + POST /api/presets/apply for
                  the sidebar-driven preset chooser (native settings hold the 17
                  individual admin fields, not the preset selector).
                  IngestRunController also exposes GET /api/ingest/runs/latest (most recent
                  COMPLETED run for the workspace, or 204) — drives the sidebar's
                  "Last checked Xm ago" + "Pending refresh" staleness indicators (P2 #2).
                  The sidebar must load /api/findings for that latest range before
                  rendering an "All clear" empty state.
                  FindingsController also exposes GET /api/findings/export?format=csv
                  (RFC 4180 attachment, P2 #3) and POST /api/findings/{id}/review
                  (admin-gated OPEN/ACKNOWLEDGED/OVERRIDDEN upsert with optional note,
                  P2 #4). GET /api/findings now embeds `review: {...} | null` inline
                  per row so the sidebar paints chip state without a second round-trip.
  clockify/       ClockifyApi (shared RestClient, SSRF guard, 429/5xx retries)
                  + DetailedReportFetcher (accepts both `timeentries` and `timeEntries`
                  response keys, returns typed DetailedReportEntry while retaining raw JSON)
                  + ClockifyRateLimiter
  config/         ClockifyAddonConfig (manifest builder), AsyncConfig (ingestExecutor
                  bounded pool), SchedulingConfig (@EnableScheduling gate),
                  SecurityHeadersFilter (HSTS conditional on request.isSecure()),
                  CorsConfig, CryptoConfig (production key fail-fast), MetricsConfig
                  (Prometheus meter names — registry auto-wired via spring-boot-starter-actuator).
  domain/         BreakRuleEngine, EntryClassifier (BREAK/WORK/IGNORED), RuleTemplatePresets,
                  SettingsWarning (cross-field validation surfaced via SessionController)
  persistence/    Entities + repositories + AES-GCM TokenCodec

src/main/resources/
  application.yaml      Env-driven Spring config (JDBC ssl/keepalive, Hikari tuning,
                        open-in-view=false, LOG_LEVEL_APP gating)
  application-dev.yaml  Local dev profile: pins DEBUG, plain-TCP localhost Postgres,
                        and opts into local Clockify base URLs for WireMock/dev probes
  db/migration/         V1__init through V16__add_workspace_holiday_scope_key
                        (Flyway, additive only). V10 extends the
                        refresh_signals status CHECK constraint with
                        CLAIMED/CONSUMED/FAILED/COALESCED, adds the
                        ingestion_run_id back-pointer column, and a
                        partial index on PENDING for the consumer's poll.
                        V11-V14 add user exemptions, approval/severity
                        controls, workspace holidays/time off, and night-shift
                        attribution. V15 retires pre-existing duplicate RUNNING rows,
                        releases their CLAIMED signals, then adds a partial
                        unique index preventing duplicate RUNNING ingests for
                        the same workspace/date range. V16 adds the
                        workspace-holiday scope_key so applies_to_user_id can
                        stay null for workspace-wide holidays.
  logback-spring.xml    Token-redacting log pattern; logger levels via application.yaml
  static/               sidebar.js, sidebar/*.js, sidebar/css/*.css, styles.css,
                        theme-init.js, i18n/en.json, icon.svg (64×64 designed mark)

src/test/...            368 green expected (JDK 21 + Postgres + Redis Testcontainers).
                        Static frontend syntax gate:
                        find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check
                        Focused sidebar behavior gate:
                        NODE_OPTIONS=--no-warnings node --test src/test/js/*.mjs
                        Spring Boot 4 test slices live in the webmvc/data-jpa/jdbc
                        test modules; do not revert imports to Boot 3 packages.
                        Testcontainers pinned to 1.21.4 in pom.xml so the
                        bundled docker-java negotiates API ≥1.44 (required
                        by Docker 25+ / Colima 29.x engines).

repo/com/cake/clockify/  Vendored Clockify SDK jar+pom (addon-sdk 1.5.3 +
                         annotation-processor 1.0.10). Eliminates the
                         GitHub Packages PAT — `pom.xml`'s
                         `vendored-clockify-sdk` repository resolves via
                         `file://${project.basedir}/repo`. To bump: drop
                         the new jar+pom under the same layout and update
                         `clockify.addon-sdk.version` in `pom.xml`.
```

## Reference (read before changing behaviour)

- `docs/api-calls.md` — outbound + inbound API shapes with live-probe evidence
- `docs/clockify-marketplace/` — canonical marketplace docs mirror
- `docs/addon-java-sdk/` — Java SDK 1.5.3 source (consumed via vendored `repo/`)
- `AGENTS.md` — operational rules for AI agents
- **Marketplace packet**: `docs/PRIVACY.md`, `docs/SECURITY.md`,
  `docs/DATA_RETENTION.md`, `docs/LEGAL_NOTICES.md`, `docs/LIVE_VALIDATION.md`
  (production install/uninstall evidence), `docs/LISTING.md` (source-of-truth
  listing copy), `docs/SUPPORT.md`, `CHANGELOG.md`.
- **Plan archive**: `~/.claude/plans/verdict-do-not-zesty-gray.md` (P0+P1
  rollout plan that produced commits `206e099…1257ffd`).

## Dev workspace + seeded test data

Workspace `69bda6b317a0c5babe34b4ff` (account `s3cvnjzji7@clockify-test.com`,
user "John Owner"). The 2026-05-12 seed below was **wiped during the
2026-05-13 live install/uninstall cycle** (see `docs/LIVE_VALIDATION.md`).
Re-seed via the dev portal Tracker before running engine-output regressions.

| Date | Work | Break | Expected (custom-basic) |
|---|---|---|---|
| 5/6 | 540 min | 0 | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/7 | 480 min | 30 min qualifying | PASS |
| 5/8 | 360 min | 3 min (below 5-min segment) | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/11 | 480 min | 30 min qualifying | PASS |
