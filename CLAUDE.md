# Break Compliance — Clockify Add-on

Java 21 / Spring Boot 3.3 marketplace add-on. Reviews whether users took required breaks.
Manifest key `break-compliance-jvm`. BASIC plan. **Read-only** scopes: `TIME_ENTRY_READ`,
`USER_READ`, `REPORTS_READ`, `WORKSPACE_READ` (no `_WRITE`, ever).

## Live deploy

| | |
|---|---|
| Host | `https://breakcompliance-production.up.railway.app` |
| Manifest | `https://breakcompliance-production.up.railway.app/manifest` |
| Railway | project `break-compliance` · service `BreakCompliance` · env `production` |
| Deploy | `railway up --service BreakCompliance --ci` (push to `main` does **not** auto-deploy) |
| Logs | `railway logs --service BreakCompliance` |
| Java SDK | `com.cake.clockify:addon-sdk:1.5.3` from GitHub Packages (see `pom.xml`) |

## Build + test

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test
```

System Maven defaults to JDK 25 which breaks Lombok — JDK 21 is required. **197 tests
green** (last verified 2026-05-12). Postgres + Redis come up via Testcontainers.

## Settings model — single editable template, preset-as-loader (§18, §21, §22, §24)

Native structured-settings page has **one tab "Break Compliance"** with 11 admin-only
fields:

| Field | Type | Default | Notes |
|---|---|---|---|
| `appliedPresetKey` | DROPDOWN | `custom-basic` | Picking a preset overwrites all 8 thresholds with that preset's values (`RuleTemplatePresets.{key}.toEntity`). |
| `workThresholdMinutes` | NUMBER | 240 | |
| `breakThresholdMinutes` | NUMBER | 15 | |
| `minBreakSegmentMinutes` | NUMBER | 5 | |
| `maxContinuousWorkMinutes` | NUMBER | 240 | |
| `gracePeriodMinutes` | NUMBER | 5 | |
| `allowSplitBreaks` | CHECKBOX | true | OFF = California meal-rule (one uninterrupted block). |
| `secondWorkThresholdMinutes` | NUMBER | 0 | 0 = second tier disabled. |
| `secondBreakThresholdMinutes` | NUMBER | 0 | 0 = second tier disabled. |
| `timezoneStrategy` | DROPDOWN | `ENTRY_TIMEZONE` | |
| `fallbackDetectionEnabled` | CHECKBOX | false | |

`InstallationService.handleSettingsUpdated`:

1. **Phase 1** — if incoming `appliedPresetKey` ≠ stored, overwrite all 8 threshold
   columns from `RuleTemplatePresets.{CUSTOM_BASIC,CALIFORNIA_STYLE,GERMANY_ARBZG_STYLE}`.
2. **Phase 2** — apply each per-field update on top. The same payload can switch preset
   AND override one field — the override wins.

`BreakRuleEngine.synthesizeWorkspaceTemplate(input)` wraps `WorkspaceSettings` into a
transient `RuleTemplate` once per evaluation and runs every user-day bucket against it.
No per-user template lookup, no `RuleTemplate` table reads at evaluation time. The
`breakcompliance_rule_templates` + `breakcompliance_template_assignments` tables are
engine-irrelevant (kept additively for back-compat).

Sidebar shows the active preset as a **clickable chip** ("Active template: …") that
opens a thresholds popover (`#active-template-chip` + `#active-template-details`). To
change settings: **Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings**.
There is no Settings button or `navigate` shortcut in the sidebar — Clockify's
`navigate` postMessage only accepts `{"type":"tracker"}`.

## Outbound Clockify API call

The add-on makes ONE outbound HTTP call:
`POST {reportsUrl}/v1/workspaces/{workspaceId}/reports/detailed`
in `DetailedReportFetcher`. Live response shape, per-env URL pattern, and pagination
via the `Last-Page` header are documented in `docs/api-calls.md`.

## Hard rules (don't break these)

| Rule | Why |
|---|---|
| Never hardcode Clockify hosts — read `backendUrl`/`reportsUrl` from JWT claims. | Dev portal uses `/report/v1/…`; production uses `reports.api.clockify.me/v1/…`. JWT carries the env-correct URL. |
| `X-Addon-Token` header (not `Authorization`) for every outbound Clockify call. | Clockify rejects `Authorization`. |
| Settings stay native structured settings. No custom `/settings` iframe. | Per `docs/clockify-marketplace/build/manifest/structured-settings.md`. |
| SETTINGS_UPDATED is the canonical object wrapper `{workspaceId, addonId, settings: [{id,value},…]}`. | Verified by 2026-05-11 live probe. `SettingsUpdatedPayload.extractUpdates` also accepts the legacy bare-array and defensive single-object shapes; unknown shapes drift-log + return 200. |
| Detailed-report response root key is `timeentries` (ALL LOWERCASE). | OpenAPI spec mislabels it as `timeEntries`. Confirmed live. See commit `f7db0e6`. |
| Detailed-report body dates are `yyyy-MM-dd'T'HH:mm:ss` (no `Z` suffix). | Server interprets dates in the user's timezone. Z suffix breaks the parse. |
| `/api/*` is `X-Addon-Token`-header-only; only `/sidebar` accepts `?auth_token=` (initial load, then JS scrubs via `history.replaceState`). | Lifecycle/webhook/api auth all fail-closed; tokens never appear in Referer or access logs. |
| `INACTIVE` installations cannot call Clockify. | `IngestionService` throws `InstallationInactiveException` → controller maps to `503 installation_inactive` (sidebar shows a friendly banner). |
| Webhook idempotency stays Redis SETNX with ≥ 24h TTL. | Clockify retries up to ~24h. |
| Flyway migrations are additive only — no destructive renames. | DB is shared across deploys; drops break rollback. Use `V<n>__add_*.sql`. |
| Production `INSTALLATION_TOKEN_KEY` must be 64 hex chars **and not** the legacy `…aa` constant or all-zero. | `CryptoConfig.validateActiveKey` fail-fasts at startup otherwise. |

## Key source files

```
src/main/java/me/apet97/breakcompliance/
  addon/
    auth/               JWT verify + claims normalization + lifecycle/webhook filters
    lifecycle/          INSTALLED/DELETED/SETTINGS_UPDATED/STATUS_CHANGED
                        + SettingsUpdatedPayload parser (object | array | single)
                        + WorkspaceDataDeletionService (DELETED wipe)
                        + PayloadDriftLogger (one-shot WARN on unknown top-level keys)
    manifest/           ManifestController (Gson serialises the SDK manifest)
    ui/                 SidebarHtmlController serves the iframe HTML shell
    webhook/            NEW_TIME_ENTRY / TIME_ENTRY_UPDATED / TIME_ENTRY_DELETED
                        + Redis SETNX idempotency (24h TTL) + RefreshSignalService
  api/                  SessionController (echoes claims + thresholds),
                        FindingsController, IngestionController, RefreshSignalsController,
                        AddonTokenAuthFilter, RequestValidator, InstallationInactiveException
  clockify/             ClockifyApi (SSRF guard, 429 Retry-After, 5xx backoff),
                        DetailedReportFetcher, ClockifyApiException, ClockifyRateLimiter
  config/               ClockifyAddonConfig (manifest builder), SecurityHeadersFilter,
                        CorsConfig, CryptoConfig (production fallback-key guard)
  domain/               BreakRuleEngine, RuleTemplatePresets, FindingDraft, EntryClassifier
  persistence/          Entities + repositories + AES-GCM TokenCodec
  util/                 WebhookPathNormalizer

src/main/resources/
  application.yaml      Env-driven Spring config + property defaults
  db/migration/         V1__init through V6__user_name (Flyway, additive only)
  logback-spring.xml    Token-redacting log pattern (%replace masks JWTs + auth headers)
  static/               sidebar.js, styles.css, icon.svg (64×64 designed mark, ~2.4 KB)

src/test/...            197 green (JDK 21, Postgres + Redis Testcontainers)
```

## Reference / vendored (read these before changing behaviour)

- `docs/clockify-marketplace/` — full marketplace canonical docs mirror
- `docs/addon-java-sdk/` — Java SDK 1.5.3 source for type/API reference
- `docs/api-calls.md` — outbound + inbound API shapes with live-probe evidence
- `AGENTS.md` — operational guide for AI agents working on this repo
- `docs/{MARKETPLACE_READINESS,PRIVACY,SECURITY,DATA_RETENTION,LEGAL_NOTICES,DESIGN_ATTRIBUTION,MCP_OPERATOR_WORKFLOW}.md`

## Dev workspace + seeded test data (2026-05-12)

Installed in dev workspace `69bda6b317a0c5babe34b4ff` (test account
`s3cvnjzji7@clockify-test.com`, user "John Owner" id `69bda6b317a0c5babe34b4fe`).

| Date | Work | Break | Expected finding (with `custom-basic` defaults) |
|---|---|---|---|
| 5/6 | 540 min | 0 | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/7 | 480 min | 30 min qualifying | PASS (no findings) |
| 5/8 | 360 min | 3 min (below 5-min segment) | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/11 | 480 min | 30 min qualifying | PASS (no findings) |
