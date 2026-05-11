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
**226 tests green** (2026-05-12). Postgres + Redis come up via Testcontainers.

## Settings model — single editable template, preset-as-loader (§18 / §22 / §24)

One native structured-settings tab "Break Compliance" with 11 admin-only fields:

| Field | Type | Default |
|---|---|---|
| `appliedPresetKey` | DROPDOWN | `custom-basic` — picking it overwrites the 8 thresholds with the preset's values |
| `workThresholdMinutes` | NUMBER | 240 |
| `breakThresholdMinutes` | NUMBER | 15 |
| `minBreakSegmentMinutes` | NUMBER | 5 |
| `maxContinuousWorkMinutes` | NUMBER | 240 |
| `gracePeriodMinutes` | NUMBER | 5 |
| `allowSplitBreaks` | CHECKBOX | true (OFF = California meal-rule) |
| `secondWorkThresholdMinutes` | NUMBER | 0 (disabled) |
| `secondBreakThresholdMinutes` | NUMBER | 0 (disabled) |
| `timezoneStrategy` | DROPDOWN | `ENTRY_TIMEZONE` |
| `fallbackDetectionEnabled` | CHECKBOX | false |

**Handler** (`InstallationService.handleSettingsUpdated`):
1. If incoming `appliedPresetKey` ≠ stored → overwrite all 8 thresholds from
   `RuleTemplatePresets.{key}.toEntity`.
2. Apply per-field updates on top — manual edits win when both arrive together.

**Engine**: `BreakRuleEngine.synthesizeWorkspaceTemplate(input)` wraps
`WorkspaceSettings` into a transient `RuleTemplate` per evaluation. No per-user
template resolution; the `breakcompliance_rule_templates` +
`breakcompliance_template_assignments` tables are engine-irrelevant (kept
additively).

**Sidebar** shows the active preset as a **clickable chip** (§24) with a
thresholds popover. Configure thresholds at:
**Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings**.

## Outbound Clockify call

One call: `POST {reportsUrl}/v1/workspaces/{workspaceId}/reports/detailed`
(`DetailedReportFetcher`). Shapes + per-env URL pattern in `docs/api-calls.md`.

## Hard rules (don't break these)

| Rule | Why |
|---|---|
| Read `backendUrl`/`reportsUrl` from JWT claims — never hardcode. | Dev portal uses `/report/v1/…`; production `reports.api.clockify.me/v1/…`. JWT has the env-correct URL. |
| `X-Addon-Token` header (not `Authorization`) for outbound. | Clockify rejects `Authorization`. |
| Settings = native structured-settings only. | Per `docs/clockify-marketplace/build/manifest/structured-settings.md`. |
| `SETTINGS_UPDATED` is the canonical wrapper `{workspaceId, addonId, settings: [{id,value},…]}` (§24). | `SettingsUpdatedPayload.extractUpdates` also accepts the legacy bare-array + defensive single-object shapes; unknown shapes drift-log + 200. |
| Detailed-report response key is `timeentries` (ALL LOWERCASE). | Spec mislabels it. Live API confirmed. |
| Dates in detailed-report body are `yyyy-MM-dd'T'HH:mm:ss` (no `Z`). | Server interprets in user timezone. |
| `type=TIME_OFF` / `type=HOLIDAY` entries are `IGNORED` by the engine (§25). | Otherwise they'd count as work → false-positive findings on PTO/holiday days. |
| `/api/*` is header-token-only. `/sidebar` accepts `?auth_token=` once, then JS scrubs it. | Lifecycle/webhook/api filters all fail-closed. |
| `INACTIVE` installations cannot reach Clockify. | `IngestionService` throws `InstallationInactiveException` → 503 `installation_inactive`. |
| Webhook idempotency = Redis SETNX, TTL ≥ 24h. | Clockify retries up to ~24h. |
| Flyway migrations are additive only. | DB shared across deploys; column drops break rollbacks. |
| Production `INSTALLATION_TOKEN_KEY` must be 64 hex chars and not legacy `…aa` or all-zero. | `CryptoConfig.validateActiveKey` fail-fasts at startup. |

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
  api/            Session, Findings, Ingestion, RefreshSignals controllers + AddonTokenAuthFilter
                  + InstallationInactiveException
  clockify/       ClockifyApi (shared RestClient, SSRF guard, 429/5xx retries)
                  + DetailedReportFetcher + ClockifyRateLimiter
  config/         ClockifyAddonConfig (manifest builder), SecurityHeadersFilter, CorsConfig,
                  CryptoConfig (production key fail-fast)
  domain/         BreakRuleEngine, EntryClassifier (BREAK/WORK/IGNORED), RuleTemplatePresets
  persistence/    Entities + repositories + AES-GCM TokenCodec

src/main/resources/
  application.yaml   Env-driven Spring config
  db/migration/      V1__init through V7__composite_indexes (Flyway, additive only)
  logback-spring.xml Token-redacting log pattern
  static/            sidebar.js + styles.css + icon.svg (64×64 designed mark)

src/test/...         226 green (JDK 21 + Postgres + Redis Testcontainers)
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
