# Agents Guide — Break Compliance

Operational guide for AI agents touching this repo. Read this **and** `CLAUDE.md` before
making changes. The hard rules below are non-negotiable.

## Mission

This add-on reviews whether Clockify users took the breaks their workspace policy
requires. It does **not** create/edit time entries, send anything to users, or manage
payroll. It is a read-only compliance reporter — fail-closed on auth, fail-loud on every
misparse.

## Before changing code

1. **Read `CLAUDE.md`** — current settings model, deploy info, hard rules.
2. **Read `docs/api-calls.md`** — exact request/response shapes for the one outbound
   call we make + the inbound webhook/lifecycle envelopes.
3. **Check `docs/clockify-marketplace/`** when adding new functionality. The canonical
   marketplace docs are mirrored locally; cite them in commit messages.
4. **Check `docs/addon-java-sdk/`** when touching manifest, lifecycle, or webhook
   plumbing — the SDK already does the verification; never reimplement.

## How to run + verify

```sh
# 1. Compile + full suite (JDK 21 required — system JDK 25 breaks Lombok).
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test
# Expect: 197+ green. Postgres + Redis spin up via Testcontainers automatically.

# 2. Targeted run.
mvn -B -ntp test -Dtest='LifecycleControllerTest,BreakRuleEngineTest'

# 3. Deploy (push to main does NOT auto-deploy).
railway up --service BreakCompliance --ci

# 4. Tail logs during a smoke-test.
railway logs --service BreakCompliance
```

## Probing live Clockify (dev workspace)

API key + workspace id are in `/tmp/clockify-livetest.env`. Never copy into the repo.

```sh
set -a; source /tmp/clockify-livetest.env; set +a

# whoami
curl -s -H "X-Api-Key: $CLOCKIFY_API_KEY" \
  https://developer.clockify.me/api/v1/user | jq .

# Detailed report (matches what the addon sends).
# IMPORTANT: dev portal lives at developer.clockify.me/report/v1/... (different path!).
curl -s -X POST \
  -H "X-Api-Key: $CLOCKIFY_API_KEY" -H "Content-Type: application/json" \
  https://developer.clockify.me/report/v1/workspaces/$CLOCKIFY_WORKSPACE_ID/reports/detailed \
  -d '{"dateRangeStart":"2026-05-04T00:00:00","dateRangeEnd":"2026-05-17T23:59:59","detailedFilter":{"page":1,"pageSize":50}}' \
  | jq '.timeentries | length'
```

The probe-lab snapshot at `/Users/15x/Downloads/WORKING/clockify-api-probe-lab/` has live
fixtures + findings for all major Clockify endpoints. Refer to its `findings/` and
`ATTENDANCEANDTIMEREPORTS.md` before debugging any API call shape.

## Hard rules (don't break these)

| Rule | Why |
|---|---|
| Read `backendUrl` / `reportsUrl` from JWT claims — never hardcode. | Dev portal uses `/report/v1/...`; production reports use `reports.api.clockify.me/v1/...`. JWT carries the env-correct URL. |
| Use `X-Addon-Token` header for outbound Clockify calls (never `Authorization`). | Clockify rejects `Authorization`. |
| Settings remain native structured settings. No `/settings` iframe. | Per `docs/clockify-marketplace/build/manifest/structured-settings.md`. |
| `SETTINGS_UPDATED` is the canonical object wrapper `{workspaceId, addonId, settings: [{id,value},…]}` — confirmed by live probe 2026-05-11. | `SettingsUpdatedPayload.extractUpdates` also accepts the legacy bare-array and defensive single-`{id,value}` shapes; anything else drift-logs and returns 200 to avoid retry storms. |
| Response key for the detailed report is `timeentries` (ALL LOWERCASE). | Spec at `docs/clockify-marketplace/...` mislabels it as `timeEntries`. Live API returns lowercase. See commit `f7db0e6`. |
| Dates in detailed-report body are `yyyy-MM-dd'T'HH:mm:ss` — **no `Z` suffix**. | The server interprets in the user's timezone. Z suffix breaks the parse. |
| `/api/*` is `X-Addon-Token`-header-only. `/sidebar` accepts `?auth_token=` query (initial iframe load), then JS scrubs it. | Lifecycle webhook auth fail-closed via `AddonTokenAuthFilter` + `WebhookAuthFilter`. |
| `INACTIVE` installations cannot reach Clockify. | `IngestionService` throws `InstallationInactiveException` → controllers map to `503 installation_inactive` (sidebar shows a friendly banner). |
| Webhook idempotency stays Redis SETNX with ≥ 24h TTL. | Clockify retries up to ~24h. Drop only if you replace with something equally durable. |
| Flyway migrations are additive only. No destructive renames. | The DB is shared across deploys; column drops break rollbacks. Use `V<n>__add_*.sql`, never `V<n>__drop_*.sql`. |
| Production `INSTALLATION_TOKEN_KEY` must be 64 hex chars **and not** the legacy `…aa` constant or all-zero. | `CryptoConfig.validateActiveKey` fail-fasts at startup. |

## Settings model (current)

Single-template-per-workspace, preset-as-loader. Eleven structured-settings fields land
on `WorkspaceSettings.customXxx` columns (the `custom_` prefix is historical — the
`customPolicyEnabled` flag no longer gates evaluation, always-on).

Preset change semantics: incoming `appliedPresetKey` ≠ stored → server overwrites all 8
threshold columns from `RuleTemplatePresets.{key}.toEntity(…)` BEFORE applying per-field
edits. Admin can change preset + tweak one field in a single SETTINGS_UPDATED payload;
the tweak wins.

The engine uses `synthesizeWorkspaceTemplate(input)` to wrap `WorkspaceSettings` into a
transient `RuleTemplate` and evaluates every user-day bucket against it. Per-user
template resolution (`RuleTemplate` + `TemplateAssignment` tables) is dead code in the
evaluation path; the tables remain only for back-compat / future per-user expansion.

## Don'ts

- **Don't add a Settings button to the sidebar.** Clockify's `navigate` postMessage only
  supports `{"type":"tracker"}` (see `docs/clockify-marketplace/build/window-events.md`).
  Arbitrary path navigation is not supported. The active-template chip + the static
  caption under the controls are the documented affordances.
- **Don't open new tabs via `window.open` for settings.** Dev portal uses a catalog
  addon-id we don't have access to from JWT claims (`claims.addonId` is the per-workspace
  installation id, a different identifier). Result: "addon unavailable" page + 401s.
- **Don't surface `RuleTemplate` lookups in new code paths.** The engine ignores them.
- **Don't drop the `Last-Page` response header parsing** if you add other paginated calls
  (it's the documented way to detect end-of-data; we currently approximate with
  `entries.size() < PAGE_SIZE` for backward compat — see `docs/api-calls.md`).
- **Don't outbound from `INACTIVE` installations.** `IngestionService` is the single
  guard; new outbound paths must consult `Installation.status` before reading the token.

## When you change behavior

1. Update tests (`src/test/java/me/apet97/breakcompliance/...`).
2. Update `CLAUDE.md` if the settings model, hard rules, or build steps change.
3. Update `docs/api-calls.md` if any outbound or inbound API shape changes.
4. Commit message format: `type(scope): short summary` matching existing history
   (`fix(reports): …`, `feat(custom-policy): …`, `refactor(settings): …`).
5. Run `mvn test` green BEFORE `git push`. CI will catch you if you don't.

## Numbered commit refs (for archaeology)

- **§1–§9** — initial takeover: contract fixes, de-minify sidebar.js, seed templates,
  settings persistence, custom policy, 401 graceful handling, settings nav, CDN
  styling, verify + deploy.
- **§10** — ArbZG typo fix + preset reorder.
- **§11** — webhook idempotency confirmed.
- **§12** — iat replay protection on user/sidebar JWTs.
- **§13** — payload-drift logger.
- **§14** — 429 Retry-After parse + retry cap.
- **§15** — verify.
- **§16** — `/v1/` path + ISO dates + response key.
  *(`f7db0e6` between §17 and §18 reverts §16's camelCase mistake; live API returns
  `timeentries` lowercase. Confirmed by live probe.)*
- **§17** — 9 granular custom policy fields.
- **§18** — single-tab redesign, preset-as-loader, engine-from-`WorkspaceSettings`.
- **§19/§20** — deferred (diagnostic logging, in-sidebar settings panel).
- **§21** — userName captured + dropdown removed + (later reverted) Settings button.
- **§22** — Settings button removed entirely, static caption added.
- **§23** — security hardening, SDK conformity audit, test-suite verification.
- **§24** — launch-readiness sweep: SETTINGS_UPDATED canonical object wrapper accepted
  (`SettingsUpdatedPayload`), sidebar UI/UX (active-template chip + thresholds popover,
  "Last checked" relative timestamp, refresh button, empty-state polish, theme-flicker
  fix, dark-mode WCAG-AA contrast, narrow-viewport responsive, full a11y), marketplace
  assets (designed 64×64 icon, real support email in `docs/PRIVACY.md`, live-test
  evidence in `MARKETPLACE_READINESS.md`); 197 tests green.
