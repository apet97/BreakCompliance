# Marketplace Submission Checklist

_Last updated: 2026-06-14._

Use this as the paste-ready release checklist for Clockify marketplace review.

| Item | Status |
|---|---|
| Code SHA validated | §37 MessageSource runtime repair committed as `ced9872` on `main`; this checklist refresh is docs-only evidence after the live deploy. |
| App version | `0.2.0` |
| Test command/result | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test` — 368 tests, 0 failures/errors/skips |
| Package command/result | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp -DskipTests package` — built `target/break-compliance-0.2.0.jar` (75M repackaged jar) |
| Live base URL | `https://breakcompliance-production.up.railway.app/healthz` observed `{"status":"ok"}` after the 2026-06-14 Railway deploy. |
| Manifest URL | `https://breakcompliance-production.up.railway.app/manifest` observed after deploy with `schemaVersion: "1.5"`, structured `settings` object, and native TXT default length 1. |
| Manifest schema | Served manifest keeps the Clockify schema 1.5 compatibility repair: object `settings`, no empty native TXT value. |
| Manifest scopes | Observed exactly `REPORTS_READ`, `TIME_ENTRY_READ`, `USER_READ`; no `_WRITE`, no `WORKSPACE_READ` |
| Privacy URL/doc | `docs/PRIVACY.md` |
| Security URL/doc | `docs/SECURITY.md` |
| Support email/doc | `petkovic.aleksandar037@gmail.com`, `docs/SUPPORT.md` |
| Data retention statement | `docs/DATA_RETENTION.md` |
| Live install evidence | Attached Clockify HAR captured a successful HTTP 201 add-on install after the manifest repair on 2026-06-14; sensitive tokens/cookies are not copied into repo docs. |
| Finding evaluation smoke | `POST /api/findings/evaluate` with the HAR date-range body returned HTTP 200 after the §37 deploy; no fresh `NoSuchMessageException`/500 entries appeared in Railway logs. |
| Live uninstall cleanup evidence | v0.1.0 evidence present; v0.2.0 cleanup refresh pending until an uninstall run is intentionally performed. |
| Screenshots/videos attached | Installed screenshot present; v0.2.0 sidebar/settings screenshots pending. The HAR supplied request/response evidence, not UI screenshots. |
| Known non-blocking follow-ups | Fresh v0.2.0 Clockify UI screenshots and uninstall cleanup evidence when `/tmp/clockify-livetest.env` is available. |

## Final submission gates

- Full JDK 21 test suite passes in a Docker/Testcontainers-ready environment.
- `mvn -B -ntp -DskipTests package` succeeds and produces the 0.2.0 jar.
- `/healthz` returns 200 for the deployed service.
- Live `/manifest` advertises schema `1.5`, validates against Clockify's
  schema 1.5, and scopes match the three read-only scopes above.
- Listing copy stays advisory: "potential break-compliance issues", never legal
  compliance guaranteed.
- No real Clockify, Railway, database, or user secrets are committed.
- `docs/LIVE_VALIDATION.md` points to current v0.2.0 evidence files and clearly
  separates public endpoint observations from same-SHA deployment proof.
