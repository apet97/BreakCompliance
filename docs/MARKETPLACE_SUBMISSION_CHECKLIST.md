# Marketplace Submission Checklist

_Last updated: 2026-06-12._

Use this as the paste-ready release checklist for Clockify marketplace review.

| Item | Status |
|---|---|
| Code SHA validated | Local branch verification complete on 2026-06-12; same-SHA Railway deployment evidence still pending |
| App version | `0.2.0` |
| Test command/result | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test` — 304 tests, 0 failures/errors/skips |
| Package command/result | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp -DskipTests package` — built `target/break-compliance-0.2.0.jar` |
| Live base URL | `https://breakcompliance-production.up.railway.app` observed 200 on 2026-06-12 |
| Manifest URL | `https://breakcompliance-production.up.railway.app/manifest` observed on 2026-06-12 |
| Manifest scopes | Observed exactly `REPORTS_READ`, `TIME_ENTRY_READ`, `USER_READ`; no `_WRITE`, no `WORKSPACE_READ` |
| Privacy URL/doc | `docs/PRIVACY.md` |
| Security URL/doc | `docs/SECURITY.md` |
| Support email/doc | `petkovic.aleksandar037@gmail.com`, `docs/SUPPORT.md` |
| Data retention statement | `docs/DATA_RETENTION.md` |
| Live install evidence | v0.1.0 evidence present; v0.2.0 refresh pending |
| Live uninstall cleanup evidence | v0.1.0 evidence present; v0.2.0 refresh pending |
| Screenshots/videos attached | Installed screenshot present; v0.2.0 sidebar/settings screenshots pending |
| Known non-blocking follow-ups | Fresh v0.2.0 Clockify UI evidence when logged-in dev workspace access is available |

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
