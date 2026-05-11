# Break Compliance — Clockify Add-on

Java 21 / Spring Boot 3.3 marketplace add-on. Reviews whether users took required breaks.
Manifest key `break-compliance-jvm`. BASIC plan. Scopes: `TIME_ENTRY_READ`, `USER_READ`,
`REPORTS_READ`, `WORKSPACE_READ` (4 read-only; no `_WRITE`).

## Live deploy

- Host: `https://breakcompliance-production.up.railway.app`
- Manifest: `…/manifest`
- Railway: project `break-compliance` · service `BreakCompliance` · env `production`
- Deploy: `railway up --service BreakCompliance --ci` (push doesn't auto-deploy)
- Logs: `railway logs --service BreakCompliance`
- SDK: `com.cake.clockify:addon-sdk:1.5.3` (GitHub Packages — see `pom.xml`)

## Build + test

```
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test
```
System Maven defaults to JDK 25 which breaks Lombok — JDK 21 is required. 165 tests green.
Postgres + Redis via Testcontainers.

## Settings model — single editable template, preset-as-loader (§18, §21, §22)

Native structured-settings page has **one tab "Break Compliance"** with 11 fields:

| Field | Type | Default | Notes |
|---|---|---|---|
| appliedPresetKey | DROPDOWN | custom-basic | Picking a preset overwrites all 8 thresholds with that preset's values |
| workThresholdMinutes | NUMBER | 240 | |
| breakThresholdMinutes | NUMBER | 15 | |
| minBreakSegmentMinutes | NUMBER | 5 | |
| maxContinuousWorkMinutes | NUMBER | 240 | |
| gracePeriodMinutes | NUMBER | 5 | |
| allowSplitBreaks | CHECKBOX | true | OFF = California meal-rule (one uninterrupted block) |
| secondWorkThresholdMinutes | NUMBER | 0 | 0 = disabled |
| secondBreakThresholdMinutes | NUMBER | 0 | 0 = disabled |
| timezoneStrategy | DROPDOWN | ENTRY_TIMEZONE | |
| fallbackDetectionEnabled | CHECKBOX | false | |

`SETTINGS_UPDATED` handler in `InstallationService.handleSettingsUpdated`:
1. If incoming `appliedPresetKey` ≠ stored, overwrite all 8 thresholds with that preset's
   values (from `RuleTemplatePresets.{CUSTOM_BASIC,CALIFORNIA_STYLE,GERMANY_ARBZG_STYLE}`).
2. Apply each per-field update on top. Same payload can switch preset AND override a
   field — the override wins.

`BreakRuleEngine` calls `synthesizeWorkspaceTemplate(input)` once and evaluates every
user-day bucket against that single synthetic template. No per-user template lookup,
no `RuleTemplate` table reads at evaluation time. The table + assignment table stay in
the DB (no destructive migration) but are engine-irrelevant.

Sidebar shows the active preset as a **read-only label** ("Active template: …").
To change settings: **Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings**.
There is no Settings button in the sidebar (`navigate` postMessage only accepts
`{"type":"tracker"}` per canonical docs; arbitrary paths aren't supported).

## Outbound Clockify API call

The add-on makes ONE outbound HTTP call: `POST {reportsUrl}/v1/workspaces/{ws}/reports/detailed`
in `DetailedReportFetcher`. Live response shape + per-env URL pattern + pagination via
`Last-Page` header documented in `docs/api-calls.md`.

## Hard rules (from canonical docs)

- **Never hardcode Clockify hosts.** Read `backendUrl`/`reportsUrl` from JWT claims.
- **`X-Addon-Token` header** (not `Authorization`) for every outbound Clockify call.
- **Settings stay native structured settings.** No custom `/settings` iframe page.
- **Lifecycle/webhook auth fail-closed.** `AddonTokenAuthFilter`, `WebhookAuthFilter`,
  per-webhook stored `authToken` validation — none of these weaken.
- **`/api/*` is header-token-only.** Only `/sidebar` accepts `?auth_token=`, then the JS
  scrubs it via `history.replaceState` and uses `X-Addon-Token` for API calls.

## Key source files

```
src/main/java/me/apet97/breakcompliance/
  addon/
    auth/               JWT verify + claims normalization
    lifecycle/          INSTALLED/DELETED/SETTINGS_UPDATED/STATUS_CHANGED + preset-loader
    ui/                 SidebarHtmlController serves the iframe HTML shell
    webhook/            NEW_TIME_ENTRY + Redis SETNX idempotency (24h TTL)
  api/                  Templates/Findings/Ingestion/SessionController, AddonTokenAuthFilter
  clockify/             ClockifyApi, DetailedReportFetcher, ClockifyApiException, rate limiter
  config/               ClockifyAddonConfig (manifest builder), SecurityHeadersFilter, CorsConfig
  domain/               BreakRuleEngine, RuleTemplatePresets, FindingDraft, EntryClassifier
  persistence/          Entities + repositories + AES-GCM TokenCodec
src/main/resources/
  application.yaml      Config + env-var overrides
  db/migration/         V1__init through V6__user_name (Flyway, additive only)
  static/               sidebar.js + styles.css + icon.svg
src/test/...            165 green (JDK 21)
```

## Reference / vendored

- `docs/clockify-marketplace/` — full marketplace canonical docs mirror
- `docs/addon-java-sdk/` — Java SDK 1.5.3 source for type/API reference
- `docs/api-calls.md` — exact outbound + inbound API shapes with live-probe evidence
- `AGENTS.md` — operational guide for AI agents working on this repo
- `docs/{MARKETPLACE_READINESS,PRIVACY,SECURITY,DATA_RETENTION,LEGAL_NOTICES,DESIGN_ATTRIBUTION,MCP_OPERATOR_WORKFLOW}.md`

## Dev workspace + seeded test data (2026-05-11)

Installed in dev workspace `69bda6b317a0c5babe34b4ff` (test account
`s3cvnjzji7@clockify-test.com`, user "John Owner" id `69bda6b317a0c5babe34b4fe`).

| Date | Work | Break | Expected finding (with custom-basic defaults) |
|---|---|---|---|
| 5/6 | 540 min | 0 | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/7 | 480 min | 30 min qualifying | PASS (no findings) |
| 5/8 | 360 min | 3 min (below 5-min segment) | MISSING_REQUIRED_BREAK + MAX_CONTINUOUS_WORK_EXCEEDED |
| 5/11 | 480 min | 30 min qualifying | PASS (no findings) |
