# Marketplace Submission Checklist

_Last updated: 2026-06-14._

Use this as the paste-ready release checklist for Clockify marketplace review.

| Item | Status |
|---|---|
| Code SHA validated | Local plan-queue worktree verification complete on 2026-06-14 from pre-commit base `2c969bb`; final pushed SHA is Git history. Same-SHA Railway deployment evidence still pending because no deploy was approved in this thread. |
| App version | `0.2.0` |
| Test command/result | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test` — 366 tests, 0 failures/errors/skips |
| Package command/result | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp -DskipTests package` — built `target/break-compliance-0.2.0.jar` (75M repackaged jar) |
| Live base URL | `https://breakcompliance-production.up.railway.app` observed 200 on 2026-06-14; environment observation only until same-SHA deploy |
| Manifest URL | `https://breakcompliance-production.up.railway.app/manifest` observed on 2026-06-14; environment observation only until same-SHA deploy |
| Manifest scopes | Observed exactly `REPORTS_READ`, `TIME_ENTRY_READ`, `USER_READ`; no `_WRITE`, no `WORKSPACE_READ` |
| Privacy URL/doc | `docs/PRIVACY.md` |
| Security URL/doc | `docs/SECURITY.md` |
| Support email/doc | `petkovic.aleksandar037@gmail.com`, `docs/SUPPORT.md` |
| Data retention statement | `docs/DATA_RETENTION.md` |
| Live install evidence | v0.1.0 evidence present; v0.2.0 refresh pending. Skipped on 2026-06-14 because `/tmp/clockify-livetest.env` was missing and no live deploy/install approval was granted. |
| Live uninstall cleanup evidence | v0.1.0 evidence present; v0.2.0 refresh pending for the same 2026-06-14 live-access reason. |
| Screenshots/videos attached | Installed screenshot present; v0.2.0 sidebar/settings screenshots pending. Skipped on 2026-06-14 because no live Clockify workspace/install was available. |
| Known non-blocking follow-ups | Fresh v0.2.0 Clockify UI evidence and same-SHA Railway deploy evidence when deployment is intentionally approved and `/tmp/clockify-livetest.env` is available. |

## Final submission gates

- Full JDK 21 test suite passes in a Docker/Testcontainers-ready environment.
- `mvn -B -ntp -DskipTests package` succeeds and produces the 0.2.0 jar.
- `/healthz` returns 200 for the deployed service.
- Live `/manifest` scopes match the three read-only scopes above.
- Listing copy stays advisory: "potential break-compliance issues", never legal
  compliance guaranteed.
- No real Clockify, Railway, database, or user secrets are committed.
- `docs/LIVE_VALIDATION.md` points to current v0.2.0 evidence files and clearly
  separates public endpoint observations from same-SHA deployment proof.
