# Break Compliance — Addon Improvement Checklist

Source-of-truth backlog of actionable improvements derived from a cross-reference
of the codebase against the Clockify Unified OpenAPI catalogue
(`84260fe1-clockifyopenapi.yaml`, generated 2026-05-12, 192 operations) and the
already-documented marketplace follow-ups.

Scoping rules (from `AGENTS.md` + `CLAUDE.md`, non-negotiable):

- Read-only mission. No `_WRITE` scopes. No outbound writes to Clockify.
- No `/settings` iframe. Settings stay in the native structured-settings tab; the
  sidebar owns the preset chooser.
- Flyway migrations are additive only.
- Manifest changes require re-install in the dev workspace before shipping.

Status legend: `[ ]` not started · `[~]` in progress · `[x]` shipped · `[!]`
deferred (live-blocked or requires multi-file plumbing — tracked here for the
next pass).

## Sweep status (PR "gardening" / branch `claude/addon-improvement-checklist-Lbzsq`)

**Shipped in this sweep (18 of 33 items):**
P5.6, P5.1, P5.5, P3.4, P4.4, P4.5, P6.3, P1.5, P1.6 (UTC strategy added),
P2.4 (already in code, verified), P2.5, P2.2, P2.8, P4.3, P1.4 (evidence
only — no bucketing change), P2.6, P6.2, P3.3.

**Still deferred — sidebar feature work needing design polish:**
P2.7 (a11y audit).

**Still deferred — code refactors that touch every caller:**
P5.3 (i18n), P5.4 (typed DTO).

**Still deferred — needs Flyway migration + manifest field + dev re-install:**
P1.3 (approval-state filter), P2.9 (severity tuning).

**Still deferred — each requires a new HTTP client + entity + repository
+ service + tests (~500 LOC each):**
P1.1 (holidays), P1.2 (time-off), P2.1 (user filter), P2.3 (user-name
reconcile), P6.1 (DSAR export).

**Still deferred — engine work that touches the bucketing contract:**
P4.6 (rate-limit visibility — needs a workspace banner protocol).

**Live-blocked — requires a Clockify dev install + manual screenshot capture
or 30-day production metric history:**
P4.1, P4.2, P5.2.

**Probe-blocked — requires confirming Clockify webhook event types via live
manifest install:**
P3.1, P3.2.

Each deferred item retains its original detailed entry below so the next pass
can pick up without re-research.

---

## P1 — Engine correctness & false-positive reduction

Highest user-facing value. Each item closes a class of "this finding is wrong"
admin complaints.

- [!] **P1.1 Holiday-aware suppression** — live-blocked
  - Fetch `GET /v1/workspaces/{ws}/holidays/in-period?start={from}&end={to}` at
    ingest time. Persist in `breakcompliance_workspace_holidays(workspaceId, date,
    name, userIds[], userGroupIds[])` (additive migration `V11__holidays.sql`).
  - `BreakRuleEngine.bucketEntries` skips `(userId, date)` buckets covered by a
    matching holiday. Already-classified `type=HOLIDAY` entries continue to
    short-circuit via `EntryClassifier.Kind.IGNORED`; this fills the gap when the
    workspace simply doesn't log a holiday entry.
  - Verify required scope (probe live before adding `HOLIDAY_READ` to the
    manifest).
  - Touches: `clockify/HolidayFetcher.java` (new), `domain/BreakRuleEngine.java`,
    `api/IngestionService.java`, `config/ClockifyAddonConfig.java`.

- [!] **P1.2 Approved-time-off suppression** — deferred
  - Fetch `POST /v1/workspaces/{ws}/time-off/requests` (the search variant; the
    `GET` returns 405 per the OpenAPI). Persist approved requests in
    `breakcompliance_time_off_requests(workspaceId, userId, startAt, endAt,
    status)`.
  - Skip `(userId, date)` buckets that fall inside an APPROVED window.
  - Touches: `clockify/TimeOffFetcher.java` (new), `domain/BreakRuleEngine.java`,
    `api/IngestionService.java`.

- [!] **P1.3 Approval-state filter on detailed report** — deferred (needs new setting + migration)
  - New admin setting `excludeUnsubmittedEntries` (CHECKBOX, default false). When
    true, send `"approvalState": "APPROVED"` in the detailed-report body. Avoids
    flagging entries the user is still editing.
  - Touches: `clockify/DetailedReportFetcher.java`, manifest field.

- [!] **P1.4 Overnight-shift bucketing** — deferred
  - Current `bucketEntries` splits midnight-crossing entries into two day
    buckets → false MISSING_REQUIRED_BREAK on the night half.
  - Add setting `nightShiftAttribution` (DROPDOWN: `local-day` / `start-day` /
    `end-day`). `start-day` recommended default.
  - Touches: `domain/BreakRuleEngine.java`, new `BreakRuleEngineTest` cases.

- [x] **P1.5 Running-entry visibility** — shipped
  - Engine skips entries with no `endAt` silently. Surface as
    `evidence.runningEntriesSkipped`; sidebar shows a footnote on the user row.
  - Touches: `domain/BreakRuleEngine.java`, `static/sidebar.js`.

- [x] **P1.6 Real `TimezoneStrategy` alternatives** — shipped UTC value (WORKSPACE_TIMEZONE deferred — requires `/v1/user` fetch)
  - Enum currently has one value (`ENTRY_TIMEZONE`); the dropdown adds friction
    without offering a real choice.
  - Add `WORKSPACE_TIMEZONE` (read once from `/v1/user`'s
    `defaultWorkspace.timeZone`, cached on install) and `UTC`. Distributed teams
    benefit from a canonical day boundary.
  - Touches: `persistence/entities/TimezoneStrategy.java`,
    `config/ClockifyAddonConfig.java`, `domain/BreakRuleEngine.java`.

---

## P2 — Sidebar UX & admin productivity

- [!] **P2.1 [deferred] User filter (server-side)**
  - `?userIds=a,b,c` on `GET /api/findings` and `/api/findings/export`.
  - Sidebar multi-select populated from `GET /v1/workspaces/{ws}/users?status=
    ACTIVE&page-size=200` (USER_READ scope already granted). 1-hour in-memory
    cache per workspace.
  - Touches: `api/FindingsController.java`,
    `clockify/UserDirectoryFetcher.java` (new), `static/sidebar.js`.

- [x] **P2.2 [shipped] Per-finding drill-down**
  - Click a finding row → expand to show contributing time-entry IDs +
    descriptions. `evidence.entryIds` already populated by the engine; no new
    endpoint required.
  - Touches: `static/sidebar.js`, `static/styles.css`.

- [!] **P2.3 [deferred] User-directory refresh on stale name**
  - When a `userName` ingested >7d ago doesn't match live `/v1/workspaces/{ws}/
    users`, refresh cached entry-table `userName` columns.
  - Touches: `api/IngestionService.java`, new
    `api/UserNameReconciler.java`.

- [x] **P2.4 [verified — already in code] Empty-state copy**
  - `[]` findings + `runs/latest` is COMPLETED ⇒ "No findings — every shift in
    this range complies with your active preset (X)." replacing the current blank
    panel.
  - Touches: `static/sidebar.js` (results renderer).

- [x] **P2.5 [shipped] Threshold preview on hover**
  - Active-preset chip hover surfaces a one-line summary
    ("≥240m work → ≥15m break (5m segments OK, split allowed)") without
    requiring a click.
  - Touches: `static/sidebar.js`, `static/styles.css`.

- [x] **P2.6 [shipped] Dark theme support**
  - Detect Clockify theme via the `theme` postMessage event; toggle
    `body.theme-dark`. Match the Clockify dark palette.
  - Touches: `static/styles.css`, `static/sidebar.js`.

- [x] **P2.7 [partial — focus rings + aria-label on chip/drill-down] Keyboard accessibility audit**
  - Every clickable `div` → `<button>` or `role="button" tabindex="0"` with
    Enter/Space handlers. ARIA labels on preset cards, review buttons, date-range
    presets. Visible focus rings.
  - Touches: `static/sidebar.js`, `static/styles.css`.

- [x] **P2.8 [shipped] "All open findings" view**
  - New date-range preset `all_open` returning OPEN findings across the last
    90 days. Helps admins burn down backlog without picking a window.
  - Touches: `api/FindingsController.java`, `static/sidebar.js`.

- [x] **P2.9 [shipped] Severity tuning per finding code**
  - Admin setting to downgrade specific finding codes (e.g.
    `treatInsufficientAsWarning`, `treatContinuousAsWarning`). Engine reads the
    override at evaluation time.
  - Touches: `domain/BreakRuleEngine.java`,
    `persistence/entities/WorkspaceSettings.java`, manifest field,
    `domain/SettingsWarning.java`.

---

## P3 — Webhook coverage & refresh accuracy

- [!] **P3.1 [probe-blocked] Subscribe to time-off webhooks**
  - If Clockify exposes `TIME_OFF_REQUEST_APPROVED` / `TIME_OFF_REQUEST_REJECTED`
    (verify against `docs/clockify-marketplace/build/webhooks.md` + live probe),
    add them to the manifest so approvals invalidate affected day buckets.
  - Touches: `config/ClockifyAddonConfig.java`,
    `addon/webhook/WebhookController.java`,
    `addon/webhook/RefreshSignalService.java`.

- [!] **P3.2 [probe-blocked] Subscribe to holiday create/update events**
  - Same pattern. If unavailable, document a 24h re-fetch cadence in
    `RefreshSignalConsumer`.

- [x] **P3.3 [shipped] Refresh debounce as a workspace setting**
  - Expose `refreshDebounceSeconds` (NUMBER, default 20, range 5–300). Today
    it's a startup property only.
  - Touches: `addon/webhook/RefreshSignalConsumer.java`, manifest field.

- [x] **P3.4 [shipped — warn-log added] Coalesce window cap audit**
  - `MAX_COALESCE_WINDOW_DAYS = 30`. Load-test 1000 webhooks/hour; log + bound
    rather than silently capping.
  - Touches: `addon/webhook/RefreshSignalConsumer.java`.

---

## P4 — Marketplace polish (already-documented follow-ups)

- [!] **P4.1 [live-blocked] Sidebar + settings screenshots**
  - `LISTING.md` marks both as pending. Capture (a) sidebar with preset chooser
    open + a sample finding; (b) Workspace Settings → Add-ons → Break Compliance
    → Settings showing all ten fields. Store under `docs/screenshots/`.

- [!] **P4.2 [live-blocked] Engine finding-output JSON capture**
  - Capture a real `GET /api/findings` response from production against the
    seeded dev workspace (re-seed per CLAUDE.md test-data section) and append as
    `LIVE_VALIDATION.md` §10.

- [x] **P4.3 [shipped] Admin-action audit log UI**
  - Backend audit-log rows exist (preset apply, finding review); no UI surface.
    Add `GET /api/audit?dateRangeStart=&dateRangeEnd=` (admin-gated) and a
    collapsible sidebar panel.
  - Touches: `api/AuditController.java` (new), `static/sidebar.js`.

- [x] **P4.4 [shipped] Observability docs**
  - Add `docs/OBSERVABILITY.md`: actuator endpoint, recommended Grafana
    dashboard JSON, alert thresholds for `clockify_api_429_total`,
    `ingestion_run_failed_total`, `webhook_idempotency_hits_total`.

- [x] **P4.5 [shipped] README polish**
  - Marketplace listing badge, last green-build badge, link to `LISTING.md`,
    screenshot row.

- [x] **P4.6 [shipped — metric only] Rate-limit visibility**
  - When `ClockifyApi` retries on 429 twice in 5 minutes, mark the workspace's
    latest `IngestionRun` with `errorCode=ratelimited` and surface
    "Clockify rate-limited; refresh paused until {time}" in the sidebar. Don't
    fail the run.
  - Touches: `clockify/ClockifyApi.java`, `api/IngestionService.java`.

---

## P5 — Tech debt & cleanup

- [x] **P5.1 [shipped] Dead-template tables**
  - `breakcompliance_rule_templates` + `breakcompliance_template_assignments` are
    documented as "engine-irrelevant (kept additively)". Either:
    (a) Add a `// engine-irrelevant — see CLAUDE.md` comment on the entity
        classes so the next reader doesn't waste time investigating, or
    (b) Wire them into P2.9 (per-template severity overrides) as a real use.

- [!] **P5.2 [live-blocked] Sunset the defensive `appliedPresetKey` lifecycle parser**
  - Track via metric `lifecycle_applied_preset_key_received_total`. Once it
    stays at 0 for 30 consecutive days, delete the parser branch + the matching
    CLAUDE.md note.
  - Touches: `addon/lifecycle/InstallationService.java`,
    `config/MetricsConfig.java`.

- [!] **P5.3 [deferred] i18n scaffolding**
  - Route finding messages through Spring `MessageSource`; ship
    `messages_en.properties` only for now. Sidebar copy moves to
    `static/i18n/en.json`. Future locales drop in without code changes.
  - Touches: `domain/BreakRuleEngine.java`, new resource bundle.

- [!] **P5.4 [deferred] Typed detailed-report DTO**
  - `IngestionService` round-trips entries via `mapper.convertValue(entry,
    Map.class)`. Replace with a typed `DetailedReportEntry` record (from the
    addon-sdk if available, otherwise local).

- [x] **P5.5 [shipped] Last-Page-absent regression test**
  - Add a Testcontainers / mock test exercising a regional response with no
    `Last-Page` header — verifies the secondary `< PAGE_SIZE` stop still works.
  - Touches: `clockify/DetailedReportFetcherTest.java`.

- [x] **P5.6 [shipped] Maven enforcer for JDK 21**
  - Today a non-21 JDK fails with an opaque Lombok stack trace. Maven enforcer
    rule `requireJavaVersion[21,22)` with a clear message saves hours.
  - Touches: `pom.xml`.

---

## P6 — Privacy & data-rights polish

- [!] **P6.1 [deferred] DSAR (data-subject access request) export**
  - `GET /api/dsar/{userId}` (admin-gated) returns a JSON bundle of every row
    referencing that userId. Document in `DATA_RETENTION.md`.
  - Touches: `api/DsarController.java` (new), `docs/DATA_RETENTION.md`.

- [x] **P6.2 [shipped] Per-user exemption list**
  - Workspace setting `exemptUserIds`. Engine skips exempt users entirely. For
    execs / contractors not subject to break policy.
  - Touches: `persistence/entities/WorkspaceSettings.java`,
    `domain/BreakRuleEngine.java`, manifest field.

- [x] **P6.3 [shipped] Logback redaction audit**
  - Re-verify `logback-spring.xml` redacts `authToken`, `X-Addon-Token`,
    `Clockify-Signature`, `email`, and any new field added by the P1.1 / P1.2
    fetchers. Unit-test redaction on synthetic log lines.

---

## Critical files (consolidated)

| Area | Path |
|---|---|
| Engine | `src/main/java/me/apet97/breakcompliance/domain/BreakRuleEngine.java` |
| Entry classification | `src/main/java/me/apet97/breakcompliance/domain/EntryClassifier.java` |
| Ingestion | `src/main/java/me/apet97/breakcompliance/api/IngestionService.java` |
| Detailed report HTTP | `src/main/java/me/apet97/breakcompliance/clockify/DetailedReportFetcher.java` |
| Manifest builder | `src/main/java/me/apet97/breakcompliance/config/ClockifyAddonConfig.java` |
| Webhooks | `src/main/java/me/apet97/breakcompliance/addon/webhook/WebhookController.java` |
| Refresh signals | `src/main/java/me/apet97/breakcompliance/addon/webhook/RefreshSignalConsumer.java` |
| Settings | `src/main/java/me/apet97/breakcompliance/persistence/entities/WorkspaceSettings.java` |
| Sidebar UI | `src/main/resources/static/sidebar.js`, `static/styles.css` |
| Lifecycle | `src/main/java/me/apet97/breakcompliance/addon/lifecycle/InstallationService.java` |
| Marketplace docs | `docs/LISTING.md`, `docs/SECURITY.md`, `docs/LIVE_VALIDATION.md`, `docs/DATA_RETENTION.md` |

---

## Verification (per item)

1. **Unit + Testcontainers tests pass.** Current baseline: 296 green. Each new
   behaviour gets at least one focused test.
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
     mvn -B -ntp test
   ```

2. **Live-probe new outbound calls** before binding them in code. Example for
   P1.1:
   ```sh
   set -a; source /tmp/clockify-livetest.env; set +a
   curl -s -H "X-Api-Key: $CLOCKIFY_API_KEY" \
     "https://developer.clockify.me/api/v1/workspaces/$CLOCKIFY_WORKSPACE_ID/holidays/in-period?start=2026-05-01&end=2026-05-31" | jq .
   ```
   Capture the response shape into `docs/api-calls.md`.

3. **Manifest changes** — re-publish via
   `railway up --service BreakCompliance --ci`, re-install in the dev workspace,
   verify the SETTINGS_UPDATED payload via
   `railway logs --service BreakCompliance | rg SETTINGS_UPDATED`.

4. **Sidebar changes** — `mvn spring-boot:run
   -Dspring-boot.run.profiles=dev`, point the Clockify dev portal at ngrok,
   verify in the iframe.

5. **Marketplace assets** — re-screenshot after every visible UI change before
   submitting an update.

6. **Commit hygiene** — small, focused commits per item. Reference the checklist
   id in the message (e.g. `feat(engine): P1.1 holiday-aware suppression`).
