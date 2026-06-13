# Clockify API calls used by Break Compliance

The add-on makes four outbound HTTP calls to Clockify (detailed report +
three suppression-cache refreshes). Everything else is inbound:
JWT-verified lifecycle webhooks and time-entry / time-off webhooks.

Everything below is **live-probed** against `developer.clockify.me` (dev workspace
`69bda6b317a0c5babe34b4ff`, user "John Owner") and `reports.api.clockify.me` (prod
workspace `65b382b606de527a7ee2b60e`). Probe-lab evidence at
`/Users/15x/Downloads/WORKING/clockify-api-probe-lab/`.

---

## 1. Outbound: Detailed Report (source-of-truth report call)

`POST {reportsUrl}/v1/workspaces/{workspaceId}/reports/detailed`

Caller: `src/main/java/me/apet97/breakcompliance/clockify/DetailedReportFetcher.java`

### Per-environment reports URL pattern

The JWT's `reportsUrl` claim already carries the env-correct base — **never hardcode**.
For reference (per `docs/clockify-marketplace/build/environments-and-regions.md` and
Clockify's published URL list):

| Environment | reportsUrl (in JWT) | Full URL after append `/v1/...` |
|---|---|---|
| Global (production) | `https://reports.api.clockify.me` | `https://reports.api.clockify.me/v1/workspaces/{ws}/reports/detailed` |
| Regional (e.g. EU) | `https://euc1.clockify.me/report` | `https://euc1.clockify.me/report/v1/workspaces/{ws}/reports/detailed` |
| Subdomain | `https://{tenant}.clockify.me/report` | same shape with subdomain |
| **Developer portal** | `https://developer.clockify.me/report` | `https://developer.clockify.me/report/v1/workspaces/{ws}/reports/detailed` |

Note the dev portal AND regional URLs put `/report/` in the **path**, not the host.

### Request

```
POST {reportsUrl}/v1/workspaces/{workspaceId}/reports/detailed
X-Addon-Token: {installation token, decrypted from breakcompliance_installations.auth_token}
Content-Type: application/json

{
  "dateRangeStart": "2026-05-04T00:00:00",
  "dateRangeEnd":   "2026-05-10T23:59:59",
  "detailedFilter": { "page": 1, "pageSize": 200 }
}
```

- **Date format**: `yyyy-MM-dd'T'HH:mm:ss` — **no `Z` suffix**. Server interprets in the
  user's timezone per the spec.
- **Only `detailedFilter` is honored** among the report sub-filter objects for this
  endpoint. Don't send `exportType`, `summaryFilter`, `weeklyFilter`, etc.
- **`approvalState`** (P1.3) is honored at the top level (live-probe
  2026-05-13: 3 entries unfiltered → 1 entry with `approvalState=APPROVED`
  against sacrificial workspace `65b382b606de527a7ee2b60e`). Sent only
  when the workspace setting `excludeUnsubmittedEntries` is on.

### Response (live shape)

```
HTTP 200
Last-Page: true|false
Content-Type: application/json

{
  "totals": [ { "totalTime": ..., "entriesCount": ..., ... } ],
  "timeentries": [                 ← ALL LOWERCASE (the OpenAPI spec mislabels)
    {
      "_id": "6a022e44554f8a011be08b15",
      "description": "Test A — should trigger MISSING_REQUIRED_BREAK …",
      "userId": "69bda6b317a0c5babe34b4fe",
      "userName": "John Owner",
      "userEmail": "s3cvnjzji7@clockify-test.com",
      "type": "REGULAR",          ← REGULAR | BREAK | HOLIDAY | TIME_OFF
      "timeInterval": {
        "start": "2026-05-06T08:00:00Z",
        "end":   "2026-05-06T17:00:00Z",
        "duration": 32400,        ← seconds
        "timeZone": "Europe/Belgrade",
        "zonedStart": "2026-05-06T10:00:00",
        "zonedEnd":   "2026-05-06T19:00:00",
        "offStart": 7200,
        "offEnd":   7200
      },
      "projectId": "...",
      "taskId": null,
      "tagIds": null,
      "billable": false,
      "approvalRequestId": null,
      "isLocked": false,
      ...
    }
  ]
}
```

### Pagination (per official docs and live behavior)

- Query params (in `detailedFilter` body, not the URL): `page` (1-indexed) and `pageSize`.
- Response header `Last-Page: true` = final page; `false` = more available. This is
  the canonical stop condition in `DetailedReportFetcher`.
- If `Last-Page` is missing, the implementation falls back to
  `entries.size() < PAGE_SIZE`. This preserves compatibility with older or regional
  builds that omit the header on the final page.
- If neither `timeentries` nor `timeEntries` is present as an array, the fetcher
  fails loudly instead of treating the workspace as empty. If every page is full
  and no final-page signal is ever observed, the hard page cap throws rather than
  silently truncating the report.

### Auth

`X-Addon-Token` header carrying the installation token. Read it server-side from the
encrypted `breakcompliance_installations.auth_token` column (decrypt via
`TokenCodec`). Never send it to the frontend. Live probes for shape discovery use
`X-Api-Key` instead — that's the user's API key, also valid auth for the same endpoint.

### Error handling

| Status | Caller behavior |
|---|---|
| 200 + `timeentries` parses | Iterate entries, upsert each into `breakcompliance_time_entries`. |
| 200 + parse fail | Throw `ClockifyApiException("failed to parse detailed report", 0, e)` — the run is recorded as FAILED for admin audit. |
| 200 + blank/null body, missing entries array, or page-cap exhaustion | Throw `ClockifyApiException` — fail-loud rather than returning false "all clear" findings. |
| 401 | `ClockifyApi.executeWithRetry` throws `ClockifyApiException(message, 401, e)`. `IngestionController` maps to HTTP 503 with `{error: "reports_unavailable", message: …}` so the sidebar shows a friendly banner instead of an opaque error. |
| 429 | Retry honoring `Retry-After` (seconds or HTTP-date), capped at 30s, max 4 retries. |
| 5xx | Retry with exponential backoff (1s → 2s → 4s, cap 30s), max 4 retries. |

---

## 1a. Outbound: holidays cache refresh (P1.1)

`GET {backendUrl}/v1/workspaces/{workspaceId}/holidays`

Caller: `src/main/java/me/apet97/breakcompliance/clockify/HolidayFetcher.java`

Returns the full holiday list and is filtered client-side to the ingest
window. The documented `/holidays/in-period?start=&end=` variant requires
an `assigned-to` ObjectId param even though OpenAPI marks it optional —
live probe 2026-05-13 against workspace `65b382b606de527a7ee2b60e`
returned `{"message":"Required request parameter 'assigned-to'...","code":3001}`
when omitted and `{"message":"Invalid ObjectId provided for field 'assigned-to'","code":501}`
when passed an empty string. The non-period endpoint sidesteps that
quirk.

### Response (live shape)

```jsonc
[
  {
    "id": "6967ed48d3e5101589b553ae",
    "name": "TestHoliday2",
    "userIds": ["64621faec4d2cc53b91fce6c", "..."],
    "userGroupIds": ["..."],
    "datePeriod": { "startDate": "2026-12-25", "endDate": "2026-12-25" },
    "everyoneIncludingNew": true,
    "occursAnnually": false,
    "automaticTimeEntryCreation": false,
    "projectId": null,
    "taskId": null
  }
]
```

Suppression rules:
- `everyoneIncludingNew=true` ⇒ workspace-wide (engine skips every user's
  bucket on that date).
- otherwise `userIds[]` ⇒ per-user.
- `userGroupIds[]` ignored for now; per-group expansion is a future
  enhancement.

## 1b. Outbound: approved time-off cache refresh (P1.2)

`POST {backendUrl}/v1/workspaces/{workspaceId}/time-off/requests`

Caller: `src/main/java/me/apet97/breakcompliance/clockify/TimeOffFetcher.java`

Body:

```json
{
  "statuses": ["APPROVED"],
  "start":  "2026-05-04T00:00:00Z",
  "end":    "2026-05-17T23:59:59Z",
  "page": 1,
  "pageSize": 200
}
```

### Response (live shape)

```jsonc
{
  "count": 25,
  "requests": [
    {
      "id":          "6a03a4a52568d3d29336df75",
      "workspaceId": "...",
      "userId":      "64621faec4d2cc53b91fce6c",
      "userName":    "Firstname Lastname",
      "timeOffPeriod": {
        "period":  { "start": "2026-12-20T23:00:00Z",
                     "end":   "2026-12-21T22:59:59Z" },
        "halfDay": false,
        "halfDayPeriod": "NOT_DEFINED",
        "halfDayHours": null
      },
      "status": {                          // ← nested object, not a flat string
        "statusType": "APPROVED",
        "note": null,
        "changedAt": "2026-05-12T22:07:46.411997904Z",
        "changedByUserId":   "...",
        "changedByUserName": "Firstname Lastname"
      },
      "policyId": "...",
      "policyName": "1111",
      "createdAt": "..."
    }
  ]
}
```

Two probe-corrected shapes (vs. earlier OpenAPI assumption):
1. `status` is `{statusType, note, changedAt, …}` — read `status.statusType`.
2. The covered window is at `timeOffPeriod.period.{start,end}`, one level
   deeper than what OpenAPI's schema (loosely typed as `object`) suggested.

Cached approved requests are stored with their exact instants. Evaluation
converts each overlapping cache row into synthetic, non-persisted `TIME_OFF`
entries clipped to the requested UTC date range; it no longer expands approved
time off into whole suppressed user-days. That keeps partial-day PTO from
hiding real work outside the approved interval.

## 1c. Outbound: user-directory refresh (P2.3)

`GET {backendUrl}/v1/workspaces/{workspaceId}/users?status=ACTIVE&page=1&page-size=200`

Caller: `src/main/java/me/apet97/breakcompliance/clockify/UserDirectoryFetcher.java`

Used after each ingest to keep cached `time_entries.user_name` in sync
with Clockify renames. Live probe confirmed the array-of-users response
shape — engine reads `id` + `name` (fallback `email`).

## 2. Inbound: lifecycle webhook envelopes

`POST {our base}/lifecycle/installed`
`POST {our base}/lifecycle/deleted`
`POST {our base}/lifecycle/settings-updated`
`POST {our base}/lifecycle/status-changed`

Caller: Clockify, after install / settings save / etc. Auth header
`X-Addon-Lifecycle-Token` — RS256 JWT verified by `ClockifyLifecycleAuthFilter` via the
SDK's `ClockifySignatureParser`.

Receivers: `src/main/java/me/apet97/breakcompliance/addon/lifecycle/LifecycleController.java`
→ `InstallationService`.

### INSTALLED payload (live)

```json
{
  "addonId": "{installation id}",       ← per-workspace, not catalog id
  "workspaceId": "{ws}",
  "authToken": "{installation token plaintext — encrypt before persisting}",
  "asUser": "{owner user id}",
  "apiUrl": "https://api.clockify.me",  ← legacy alias; ClaimsNormalizer maps to backendUrl
  "webhooks": [
    { "path": "https://addon/webhook/new-time-entry",
      "event": "NEW_TIME_ENTRY",
      "authToken": "{per-webhook secret — also encrypt}" }
  ]
}
```

### SETTINGS_UPDATED payload (live — canonical object wrapper)

The dev portal and the canonical Clockify docs
(`docs/clockify-marketplace/build/manifest/lifecycle.md:96-134`) wrap the
array of changed fields inside an object that also carries
`workspaceId`/`addonId`/`asUser`. Verified against the live developer
portal on 2026-05-11 (Railway log
`HttpMessageNotReadableException: ...from Object value` confirmed the wrapper
shape — the §24 fix accepts it).

```json
{
  "workspaceId": "69bda6b317a0c5babe34b4ff",
  "addonId":     "6a024b931421fb8f26af8100",
  "settings": [
    { "id": "workThresholdMinutes",   "name": "Work threshold (minutes)",   "value": 360 },
    { "id": "secondWorkThresholdMinutes", "name": "Second-tier work threshold (minutes)", "value": 540 }
  ]
}
```

> **Note** — `appliedPresetKey` is no longer in the manifest. The lifecycle
> handler still accepts it defensively (so any cached delivery still resolves
> the preset's threshold values), but preset selection in current builds goes
> through `POST /api/presets/apply` from the sidebar; see §4 below.

`SettingsUpdatedPayload.extractUpdates` accepts three shapes for resilience:

| Shape | Source | Behaviour |
|---|---|---|
| `{settings: [...]}` (object wrapper) | Live dev portal + canonical docs | Unwrapped, list passed to handler. |
| `[{id, value}, …]` (bare array) | Earlier dev-portal builds, legacy spec | Used as-is. |
| `{id, value}` (single field) | Defensive | Wrapped as singleton list. |
| Anything else | Drift | `PayloadDriftLogger` WARN once, 200 returned. |

`InstallationService.handleSettingsUpdated`:
1. Applies per-field edits to the ten native threshold/checkbox fields.
2. Defensive: if `appliedPresetKey` appears (legacy / cached), resolves the
   value via `RuleTemplatePresets.fromManifestLabel` (or the slug fallback)
   and overwrites all 8 threshold columns. New code paths use
   `POST /api/presets/apply` instead — Clockify won't push the field now that
   it's gone from the manifest.
3. Runs `SettingsWarning.validate(...)` over the merged settings and stores
   the JSON-encoded result on `workspace_settings.validation_warnings`. The
   sidebar surfaces these via `/api/session` as a dismissible banner.

---

## 3. Inbound: time-entry webhooks

`POST {our base}/webhook/new-time-entry`
`POST {our base}/webhook/time-entry-updated`
`POST {our base}/webhook/time-entry-deleted`

Caller: Clockify, on NEW_TIME_ENTRY. Two auth checks:
1. RS256 `Clockify-Signature` JWT (verified by SDK).
2. `Clockify-Webhook-Event-Type` header matches the route's expected event.
3. (Third check) — the signature's authToken claim matches the stored per-webhook
   `authToken` from the INSTALLED payload. See `WebhookAuthFilter`.

Idempotency: Redis SETNX keyed on the workspace, event type, and body hash, 24h TTL.
Duplicate deliveries return 204 immediately without re-processing.

Receiver: `src/main/java/me/apet97/breakcompliance/addon/webhook/WebhookController.java`.

Processing flow:

1. `WebhookController` authenticates, dedupes, increments webhook metrics, extracts a
   best-effort `dateHint` from `timeInterval.start`, and stores a `PENDING`
   `breakcompliance_refresh_signals` row.
2. `RefreshSignalConsumer` runs on a scheduled fixed delay (default 30s), drains
   pending signals older than the debounce window (default 20s), groups them by
   workspace, and computes the smallest safe date window from the hints.
3. If a `RUNNING` `IngestionRun` already covers that workspace/window, the signals are
   marked `COALESCED` and back-pointed with `ingestion_run_id`.
4. Otherwise the consumer claims the signals, dispatches an async ingest through
   `IngestionService.beginAsyncForRefresh`, re-evaluates findings after a successful
   completion, and marks the signals `CONSUMED`. Completion is exposed only after
   detailed-report entries are persisted and the holiday/time-off suppression
   refresh attempt returns. Failures become `FAILED`.
5. `IngestionRunReaper` marks stale `RUNNING` runs as `FAILED` and releases any
   claimed signals back to `PENDING` so a later poll can recover.

---

## 3a. Inbound: time-off webhooks

`POST {our base}/webhook/time-off-approved`
`POST {our base}/webhook/time-off-rejected`
`POST {our base}/webhook/time-off-withdrawn`

Receiver: `src/main/java/me/apet97/breakcompliance/addon/webhook/WebhookController.java`.

The webhook body is best-effort only; auth and idempotency are the same as
time-entry webhooks. For `dateHint`, the receiver first reads the live-probed
time-off request shape at `timeOffPeriod.period.start`, then defensively falls
back to legacy `timeOffPeriod.start`. Malformed or missing starts still record
the signal with a null hint so the consumer can use its fallback refresh window.

```jsonc
{
  "id": "pto-1",
  "timeOffPeriod": {
    "period": {
      "start": "2026-12-20T23:00:00Z",
      "end": "2026-12-21T22:59:59Z"
    },
    "halfDay": false
  }
}
```

## 4. Sidebar-facing API (`/api/*` — verified by `AddonTokenAuthFilter`)

These endpoints exist because we want sidebar UX that the native settings tab
can't deliver — preset selection that previews values before applying, async
ingest with real progress, and cross-field validation surfaced as a banner.

### `GET /api/presets`

Returns the catalogue the sidebar's "Switch…" panel renders as cards. Auth
required; no role gate.

```json
{
  "presets": [
    {
      "key": "custom-basic",
      "label": "Custom (Editable Defaults)",
      "description": "Neutral starter. All thresholds are placeholders; admins edit them …",
      "thresholds": {
        "workThresholdMinutes": 240,
        "breakThresholdMinutes": 15,
        "minBreakSegmentMinutes": 5,
        "maxContinuousWorkMinutes": 240,
        "gracePeriodMinutes": 5,
        "allowSplitBreaks": true,
        "secondWorkThresholdMinutes": null,
        "secondBreakThresholdMinutes": null
      }
    },
    { "key": "california-style", "label": "California (IWC Meal & Rest)", "thresholds": { /* … */ } },
    { "key": "germany-arbzg-style", "label": "Germany (ArbZG §3 & §4)", "thresholds": { /* … */ } }
  ]
}
```

### `POST /api/presets/apply`

Admin-only (`workspaceRole=ADMIN`). Body: `{"presetKey": "california-style"}`.
Funnels through `InstallationService.applyPreset`, which overwrites the eight
threshold columns, records `appliedPresetKey`, re-runs `SettingsWarning.validate`,
and saves in one transaction. Returns the updated active template:

```json
{
  "appliedPresetKey": "california-style",
  "workThresholdMinutes": 300,
  "breakThresholdMinutes": 30,
  "minBreakSegmentMinutes": 10,
  "maxContinuousWorkMinutes": 300,
  "gracePeriodMinutes": 5,
  "allowSplitBreaks": false,
  "secondWorkThresholdMinutes": 600,
  "secondBreakThresholdMinutes": 30
}
```

Errors:
| HTTP | Code | When |
|---|---|---|
| 400 | `missing_preset_key` | Body is empty or omits the field. |
| 400 | `unknown_preset_key` | `presetKey` is not one of `RuleTemplatePresets.ALL`. |
| 409 | `no_workspace_settings` | The workspace has no settings row yet — caller re-opens the addon to trigger an install first. |
| 403 | — | Non-admin caller (`RequestValidator.requireAdmin`). |

### `GET /api/ingest/runs/{runId}`

Polled by the sidebar after a `POST /api/ingest/detailed-report` returns 202.
Cadence: ~800 ms growing to a 4 s ceiling. Scoped to the JWT's workspace —
a run from a different workspace returns 404 regardless of guessability.

```json
{
  "id": "f1c4…",
  "workspaceId": "69bda6b3…",
  "status": "COMPLETED",
  "entriesProcessed": 1234,
  "dateRangeStart": "2026-05-04",
  "dateRangeEnd": "2026-05-17",
  "errorCode": "",
  "createdAt": "2026-05-12T09:48:32.110Z",
  "completedAt": "2026-05-12T09:49:14.220Z"
}
```

`status` cycles `RUNNING → COMPLETED | FAILED`. `COMPLETED` means persisted
entries and the best-effort suppression refresh attempt are both finished; the
sidebar should not evaluate or show "All clear" from only an in-memory empty
findings array. On `FAILED` the sidebar maps
`errorCode` to user-readable copy (`ClockifyApi:401` → "Reports API unavailable
in this workspace", others → "Ingestion failed (\<code\>)").

---

## 4. Reference

- Probe-lab live evidence: `/Users/15x/Downloads/WORKING/clockify-api-probe-lab/`
  - `ATTENDANCEANDTIMEREPORTS.md` — full OpenAPI spec for reports endpoints (note the
    `timeEntries` vs `timeentries` spec-mislabel — see §0 below).
  - `findings/SUMMARY.md` — cross-endpoint bug findings with go-clockify deltas.
- Canonical marketplace docs: `docs/clockify-marketplace/`
  - `build/environments-and-regions.md` — JWT claim list + URL derivation rule.
  - `build/window-events.md` — postMessage event catalog (navigate only takes `tracker`).
  - `build/authentication-and-authorization.md` — token types + claim list.
  - `build/manifest/*.md` — manifest, lifecycle, components, webhooks, structured-settings.
- Java SDK source: `docs/addon-java-sdk/addon-sdk/src/`
- DTOs: `src/main/java/me/apet97/breakcompliance/api/api-types.*`

### §0 — Spec mismatches found via live probe

| Field | Spec says | Live API returns | Decision |
|---|---|---|---|
| Detailed-report response root key | `timeEntries` | `timeentries` (all-lowercase) | Use lowercase. See commit `f7db0e6`. |
| Date format in body | `YYYY-MM-DDTHH:MM:SS.ssssss` | accepts `YYYY-MM-DDTHH:MM:SS` (no fractional) | Use seconds-only `yyyy-MM-dd'T'HH:mm:ss`. |
| `exportType` field | optional | ignored for detailed-report | Don't send. |
| `SETTINGS_UPDATED` body shape | (`api-calls.md` previously documented a bare array) | Canonical Clockify docs and the live dev portal both deliver `{workspaceId, addonId, settings: [...]}` | Parser accepts wrapper, bare array, and singleton object. See §24 + `SettingsUpdatedPayload`. |

---

## Quick reproductions

```bash
# Set up
set -a; source /tmp/clockify-livetest.env; set +a
DEV_KEY="$CLOCKIFY_API_KEY"
DEV_WS="$CLOCKIFY_WORKSPACE_ID"

# Detailed report — dev workspace
curl -s -X POST \
  -H "X-Api-Key: $DEV_KEY" -H "Content-Type: application/json" \
  "https://developer.clockify.me/report/v1/workspaces/$DEV_WS/reports/detailed" \
  -d '{"dateRangeStart":"2026-05-04T00:00:00","dateRangeEnd":"2026-05-17T23:59:59","detailedFilter":{"page":1,"pageSize":50}}' \
  | jq '.timeentries[] | {id: ._id, type, user: .userName, dur: .timeInterval.duration}'

# Create a time entry (for test data)
curl -s -X POST \
  -H "X-Api-Key: $DEV_KEY" -H "Content-Type: application/json" \
  "https://developer.clockify.me/api/v1/workspaces/$DEV_WS/time-entries" \
  -d '{"start":"2026-05-11T08:00:00Z","end":"2026-05-11T12:00:00Z","description":"Test","type":"REGULAR"}'
```
