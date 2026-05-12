# Break Compliance — Clockify Add-on

Java 21 / Spring Boot 3.3 marketplace add-on. Reviews whether users took required breaks.
Manifest key `break-compliance-jvm`. BASIC plan. **Read-only** scopes:
`TIME_ENTRY_READ`, `USER_READ`, `REPORTS_READ`, `WORKSPACE_READ` (no `_WRITE`, ever).

## Live deploy

| | |
|---|---|
| Host | `https://breakcompliance-production.up.railway.app` |
| Manifest | `…/manifest` |
| Railway | project `break-compliance` · service `BreakCompliance` · env `production` |
| Deploy | `railway up --service BreakCompliance --ci` (push does **not** auto-deploy) |
| Logs | `railway logs --service BreakCompliance` |
| Java SDK | `com.cake.clockify:addon-sdk:1.5.3` from GitHub Packages |

## Build + test

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test
```

System Maven defaults to JDK 25 which breaks Lombok — JDK 21 required.
**255 tests green** (2026-05-12). Postgres + Redis come up via Testcontainers.

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

**Native structured-settings tab "Break Compliance"** — 10 admin-only fields
for fine-tuning individual thresholds:

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
| `fallbackDetectionEnabled` | CHECKBOX | false |

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

**Sidebar** shows the active preset as a **clickable chip** with a thresholds
popover, a **Matches preset / Customized — Reset?** pill next to it, and a
**Switch…** button that opens the preset chooser. Fine-tune individual fields
at: **Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings**.

## Outbound Clockify call

One call: `POST {reportsUrl}/v1/workspaces/{workspaceId}/reports/detailed`
(`DetailedReportFetcher`). Shapes + per-env URL pattern in `docs/api-calls.md`.

## Hard rules (don't break these)

| Rule | Why |
|---|---|
| Read `backendUrl`/`reportsUrl` from JWT claims — never hardcode. | Dev portal uses `/report/v1/…`; production `reports.api.clockify.me/v1/…`. JWT has the env-correct URL. |
| `X-Addon-Token` header (not `Authorization`) for outbound. | Clockify rejects `Authorization`. |
| Threshold fields = native structured-settings; preset selection = sidebar. | Clockify renders each native field independently and never re-fetches siblings after a change, so a backend-driven cross-field write isn't visible until reload. Sidebar lets us preview, confirm, and apply atomically. |
| `SETTINGS_UPDATED` is the canonical wrapper `{workspaceId, addonId, settings: [{id,value},…]}` (§24). | `SettingsUpdatedPayload.extractUpdates` also accepts the legacy bare-array + defensive single-object shapes; unknown shapes drift-log + 200. |
| Detailed-report response key is `timeentries` (ALL LOWERCASE). | Spec mislabels it. Live API confirmed. |
| Dates in detailed-report body are `yyyy-MM-dd'T'HH:mm:ss` (no `Z`). | Server interprets in user timezone. |
| `type=TIME_OFF` / `type=HOLIDAY` entries are `IGNORED` by the engine (§25). | Otherwise they'd count as work → false-positive findings on PTO/holiday days. |
| `/api/*` is header-token-only. `/sidebar` accepts `?auth_token=` once, then JS scrubs it. | Lifecycle/webhook/api filters all fail-closed. |
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
    manifest/     ManifestController (Gson serialises the SDK manifest)
    ui/           SidebarHtmlController serves the iframe HTML shell
    webhook/      NEW/UPDATED/DELETED time-entry + Redis SETNX (24h TTL) + RefreshSignalService
  api/            Session, Findings, Ingestion, IngestRun, Presets, RefreshSignals
                  controllers + AddonTokenAuthFilter + InstallationInactiveException.
                  IngestionService runs in 3 phases (prepare/fetch/finalize), dispatched
                  async via ingestExecutor; sidebar polls /api/ingest/runs/{id} for status.
                  PresetController serves GET /api/presets + POST /api/presets/apply for
                  the sidebar-driven preset chooser (native settings only holds the 10
                  per-field thresholds).
  clockify/       ClockifyApi (shared RestClient, SSRF guard, 429/5xx retries)
                  + DetailedReportFetcher + ClockifyRateLimiter
  config/         ClockifyAddonConfig (manifest builder), AsyncConfig (ingestExecutor
                  bounded pool), SecurityHeadersFilter, CorsConfig, CryptoConfig
                  (production key fail-fast)
  domain/         BreakRuleEngine, EntryClassifier (BREAK/WORK/IGNORED), RuleTemplatePresets,
                  SettingsWarning (cross-field validation surfaced via SessionController)
  persistence/    Entities + repositories + AES-GCM TokenCodec

src/main/resources/
  application.yaml      Env-driven Spring config (JDBC ssl/keepalive, Hikari tuning,
                        open-in-view=false, LOG_LEVEL_APP gating)
  application-dev.yaml  Local dev profile: pins DEBUG, plain-TCP localhost Postgres
  db/migration/         V1__init through V9__workspace_settings_validation_warnings
                        (Flyway, additive only)
  logback-spring.xml    Token-redacting log pattern; logger levels via application.yaml
  static/               sidebar.js + styles.css + icon.svg (64×64 designed mark)

src/test/...            255 green (JDK 21 + Postgres + Redis Testcontainers)
```

## Reference (read before changing behaviour)

- `docs/api-calls.md` — outbound + inbound API shapes with live-probe evidence
- `docs/clockify-marketplace/` — canonical marketplace docs mirror
- `docs/addon-java-sdk/` — Java SDK 1.5.3 source
- `AGENTS.md` — operational rules for AI agents

## Dev workspace + seeded test data (2026-05-12)

Installed in workspace `69bda6b317a0c5babe34b4ff` (account
`s3cvnjzji7@clockify-test.com`, user "John Owner").

| Date | Work | Break | Expected (custom-basic) |
|---|---|---|---|
| 5/6 | 540 min | 0 | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/7 | 480 min | 30 min qualifying | PASS |
| 5/8 | 360 min | 3 min (below 5-min segment) | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/11 | 480 min | 30 min qualifying | PASS |
