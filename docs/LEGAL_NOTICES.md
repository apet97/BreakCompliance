# Legal Notices — Break Compliance for Clockify

_Last updated: 2026-05-13._

## Advisory only — not legal advice

Break Compliance produces **advisory findings** about whether ingested time entries meet a configurable break policy. The presets named `Germany (ArbZG §3 & §4)` and `California (IWC Meal & Rest)` are starter templates **inspired by the structure** of those statutes. They are not claims of legal compliance with any specific law, regulation, or contract.

- The add-on **never** modifies time entries, **never** starts or stops timers, **never** enforces a rule against a worker, and **never** produces a record intended as legal evidence.
- Workspace admins are responsible for tuning template thresholds to match their company policy, jurisdiction, and worker contracts. Verify with qualified counsel before relying on Break Compliance findings for any regulated decision.
- Break Compliance is **not** a workforce-management or payroll system.

## Trademarks and third-party marks

- "Clockify" is a trademark of CAKE.com Inc. Break Compliance is an independent add-on; CAKE.com endorsement is neither claimed nor implied.
- "Railway" is a trademark of Railway Corp. The add-on is hosted on Railway but is not affiliated with Railway Corp.
- "PostgreSQL" is a trademark of the PostgreSQL Global Development Group.
- "Redis" is a trademark of Redis Ltd.

## Open-source components

Major dependencies and their licenses:

- **Spring Boot 3.3** (Apache 2.0) — application framework.
- **Hibernate ORM** (LGPL 2.1) — persistence layer.
- **PostgreSQL JDBC driver** (BSD-2) — database driver.
- **Flyway Community** (Apache 2.0) — schema migrations.
- **Lettuce** (Apache 2.0) — Redis client.
- **JJWT** (Apache 2.0) — JWT parsing and signing.
- **Gson** (Apache 2.0) — JSON serialisation for the SDK-generated manifest.
- **Jackson** (Apache 2.0) — JSON for Spring MVC.
- **Logback** (EPL 1.0 + LGPL 2.1) — logging.
- **`com.cake.clockify:addon-sdk`** (license per CAKE.com — see the SDK repo) — Clockify marketplace SDK.

Full transitive list is reproducible from `pom.xml` via `mvn dependency:tree`.

## Liability disclaimer

THE ADD-ON IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND. THE OPERATOR AND CONTRIBUTORS ARE NOT LIABLE FOR DAMAGES ARISING FROM:

- Reliance on findings without independent verification.
- Time entries that violate company policy or jurisdictional rules but are not flagged (false negatives — for example, if the workspace admin has not assigned a rule template to a user or has misconfigured fallback detection).
- Findings that incorrectly flag compliant time entries (false positives — for example, if break entries are not labelled `type=BREAK` and fallback detection is disabled).
- Loss of data due to a Railway, Postgres, or Redis outage. The hosting provider's SLA and the operator's backup configuration determine durability.
- Misuse, including using the add-on as a basis for disciplinary action against workers without independent review.

## Data processor relationship

When deployed in a workspace, the workspace admin is the **data controller** for time-entry and user data; the add-on operator acts as a **data processor** for the workspace's data. The data processing addendum (DPA) is operator-supplied on request.
