# Clockify API calls used by Break Compliance

The add-on makes ONE outbound HTTP call to Clockify (detailed report). Everything else
is inbound: JWT-verified lifecycle webhooks and time-entry webhooks.

Everything below is **live-probed** against `developer.clockify.me` (dev workspace
`69bda6b317a0c5babe34b4ff`, user "John Owner") and `reports.api.clockify.me` (prod
workspace `65b382b606de527a7ee2b60e`). Probe-lab evidence at
`/Users/15x/Downloads/WORKING/clockify-api-probe-lab/`.

---

## 1. Outbound: Detailed Report (the only call we make)

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

### Pagination (per official docs)

- Query params (in `detailedFilter` body, not the URL): `page` (1-indexed) and `pageSize`.
- Response header `Last-Page: true` = final page; `false` = more available.
- Current implementation stops when `entries.size() < PAGE_SIZE`. The `Last-Page` header
  is the canonical stop condition — adopt it when convenient.

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
| 401 | `ClockifyApi.executeWithRetry` throws `ClockifyApiException(message, 401, e)`. `IngestionController` maps to HTTP 503 with `{error: "reports_unavailable", message: …}` so the sidebar shows a friendly banner instead of an opaque error. |
| 429 | Retry honoring `Retry-After` (seconds or HTTP-date), capped at 30s, max 4 retries. |
| 5xx | Retry with exponential backoff (1s → 2s → 4s, cap 30s), max 4 retries. |

---

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

### SETTINGS_UPDATED payload (live — array of changed fields)

```json
[
  { "id": "appliedPresetKey", "value": "germany-arbzg-style" },
  { "id": "workThresholdMinutes", "value": 360 }
]
```

`InstallationService.handleSettingsUpdated`:
1. Scans for `appliedPresetKey` change → overwrites all 8 threshold columns from
   `RuleTemplatePresets.{value}.toEntity(...)`.
2. Applies per-field edits on top.

---

## 3. Inbound: time-entry webhook

`POST {our base}/webhook/new-time-entry`

Caller: Clockify, on NEW_TIME_ENTRY. Two auth checks:
1. RS256 `Clockify-Signature` JWT (verified by SDK).
2. `Clockify-Webhook-Event-Type` header matches the route's expected event.
3. (Third check) — the signature's authToken claim matches the stored per-webhook
   `authToken` from the INSTALLED payload. See `WebhookAuthFilter`.

Idempotency: Redis SETNX keyed on `webhook:NEW_TIME_ENTRY:{sha256(body)}`, 24h TTL.
Duplicate deliveries return 204 immediately without re-processing.

Receiver: `src/main/java/me/apet97/breakcompliance/addon/webhook/WebhookController.java`.
Currently records a "refresh signal" so the sidebar can know to re-ingest; doesn't
trigger live engine evaluation.

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
