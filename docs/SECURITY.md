# Security — Break Compliance for Clockify

_Last updated: 2026-05-11._

## Threat model

The add-on sits between Clockify and the workspace admin's browser. The three trust boundaries are:

1. **The Spring Boot server** — receives Clockify webhook deliveries, sidebar fetches, and lifecycle deliveries. Holds the AES encryption key at runtime.
2. **Postgres** — stores all workspace-scoped data, with ciphertext for the installation token and per-webhook auth tokens. Scoped by `workspace_id` on every primary key.
3. **Redis** — transient counters for rate limiting + idempotency. No durable data, no tokens.

Specific threats handled:

- **Forged Clockify-signed tokens** — Mitigated by `ClockifySignatureParser` (RS256 + `iss=clockify` + `type=addon` + `sub=break-compliance-jvm`) plus an explicit `exp` requirement and post-normalisation `workspaceId`+`addonId` presence check in the filter.
- **Replay of a valid token against a different addon's webhook route** — Mitigated by the third check in `WebhookAuthFilter`: the `authToken` claim in the JWT must match the decrypted stored `authToken` for `(workspace_id, addon_id, normalized_path)`.
- **Cross-workspace data probing** — Mitigated by composite PKs with `workspace_id` leading every tenant-scoped table; the controllers derive `workspaceId` only from verified claims, never from the request body or query.
- **Server-side credential leak via response or log** — Mitigated by the AES-GCM-256 token codec keeping plaintext out of the database, Logback `%replace` converters masking JWT triplets and token-header patterns before any line reaches the appender, and `Cache-Control: no-store` on every `/api/*` response.
- **SSRF via tampered `backendUrl` claim** — Mitigated by `ClockifyApi`'s scheme + host allowlist (`https://` + `*.clockify.me`, `localhost` allowed only for tests).
- **Double-slash webhook path / addon-id confusion** — Mitigated by `WebhookPathNormalizer` (collapses `//` runs, strips trailing slash) and by using `claims.addonId` (the Clockify-generated id) as the install identifier rather than the manifest key.
- **Webhook delivery double-processing on retry** — Mitigated by Redis `SETNX` idempotency keyed by `sha256(eventType || 0x00 || body)` with 24-hour TTL.

## Encryption

- **Algorithm:** `AES/GCM/NoPadding`, 256-bit key, 12-byte random IV per encrypt, 128-bit auth tag.
- **Key source:** env var `INSTALLATION_TOKEN_KEY` (64 hex characters → 32 bytes). The active key id is `INSTALLATION_TOKEN_KEY_ID` (defaults to `default`). Rotation supported by mapping multiple key ids to keys in `breakcompliance.crypto.keys`.
- **Encoded form:** `IV(12) || ciphertext || authTag(16)` packed as `BYTEA`; the `keyId` is stored alongside in a sibling column.
- **Failure mode:** any decryption error throws `TokenCodecException`; callers must surface this as a 5xx, never silently fallback.

## Transport

- TLS 1.2+ enforced at Railway's load balancer. HSTS toggled on via `ENABLE_HSTS=true` once the custom domain is fully verified end-to-end.
- The add-on rejects non-HTTPS `backendUrl`/`reportsUrl` claims (except `localhost` for tests).

## Per-request hardening

- **CSP:** `default-src 'self'; frame-ancestors https://app.clockify.me [+ EXTRA_FRAME_ANCESTORS]` so the iframe is embeddable only by Clockify's app shell (with dev-portal allowance toggled by an env var during testing).
- **X-Content-Type-Options:** `nosniff`.
- **Referrer-Policy:** `no-referrer`.
- **Permissions-Policy:** `camera=() microphone=() geolocation=()`.
- **Cache-Control:** `no-store` on every `/api/*` response.

## Secret hygiene

- All secrets (AES key, GitHub Packages PAT, Clockify public-key URL override) flow through environment variables. Nothing is committed to the repo.
- Logback `%replace` converters mask JWT triplets (`eyJ...`) and `authToken|X-Addon-Token|X-Addon-Lifecycle-Token|Clockify-Signature` header/field values before any line reaches stdout.
- The deploy pipeline runs from a clean working tree; the Maven build never executes downloaded scripts.

## Dependency hygiene

- Dependencies are pinned via `pom.xml` parent + explicit version overrides where needed. Spring Boot's BOM controls transitive versions.
- Security advisories from Spring, Hibernate, JJWT, the Clockify Java SDK, or any test dependency trigger an out-of-band update. The CI job runs `mvn verify` on every push so a vulnerable upgrade gets caught early.

## Incident response

- **Suspected token leak:** rotate `INSTALLATION_TOKEN_KEY` (generate new 64-hex value, update Railway env var, redeploy). New encryptions use the new key id; existing rows can be re-encrypted via a one-off migration job or left as-is (the codec still decrypts old key ids while the old key remains mapped).
- **Suspected database compromise:** uninstall the addon in affected workspaces (DELETED lifecycle clears tokens), rotate the key, restore Postgres from a known-clean snapshot.
- **Suspected Clockify-side credential leak:** uninstall + reinstall forces Clockify to issue fresh installation + webhook auth tokens.

## Open follow-ups

- Centralised audit log for admin actions (currently captured in `breakcompliance_audit_logs` but no UI surface yet).
- Out-of-band log shipping with a downstream redaction policy (today: stdout + the application-level Logback mask).
- Periodic external penetration test on the production endpoint.
