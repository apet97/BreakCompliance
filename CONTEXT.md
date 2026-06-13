# Break Compliance Context

## Mission

Break Compliance is a read-only Clockify marketplace add-on. It reviews whether
workspace users took required breaks; it never writes Clockify time entries,
never sends Clockify messages, and never adds outbound write scopes.

## Current Domain Model

- Clockify Detailed Report is the source of truth for time entries.
- `WorkspaceSettings` is the active policy surface. The engine synthesizes one
  transient `RuleTemplate` from the settings row for every evaluation.
- Native structured settings own threshold fields; the sidebar owns preset
  selection because Clockify's settings UI does not refresh sibling fields after
  a backend preset write.
- Finding text is generated through Spring `MessageSource` using
  `messages.properties` as the root bundle plus `messages_en.properties` as the
  English locale bundle; the root file is required for Spring Boot to
  auto-configure a real `MessageSource`. The sidebar's high-visibility shell
  copy is loaded from first-party `/i18n/en.json`.
- `RuleTemplate`, `TemplateAssignment`, and `GroupMembership` tables remain for
  backward-compatible deletion and historical data, but current engine input
  does not use them.
- Holidays and approved time off are cached suppression data. Workspace-wide
  holidays suppress every user's bucket for that date; user-specific holidays
  suppress only matching users. Approved time-off cache rows are converted into
  evaluation-only `TIME_OFF` entries so partial-day requests keep interval
  precision and same-day work outside PTO still evaluates. Group-only holidays
  are skipped until group membership expansion exists.

## Key Runtime Invariants

- `backendUrl` and `reportsUrl` come from verified JWT claims or persisted install
  state. Production outbound Clockify URLs must be HTTPS `*.clockify.me`;
  localhost requires the explicit dev/test opt-in property.
- `/api/*` accepts `X-Addon-Token` only. `/sidebar` may accept `?auth_token=`
  for initial iframe navigation and then scrubs it from the URL.
- `script-src 'self'` is intentional. `/sidebar` must not render inline scripts;
  first-paint theme bootstrap lives in `/theme-init.js`, and i18n dictionaries
  are same-origin static JSON assets.
- Only one RUNNING ingest may exist for the same `(workspaceId, dateRange)`; V15
  enforces this with a partial unique index and startup cleanup.
- A run is not `COMPLETED` until detailed-report entries are persisted and the
  holiday, time-off, and user-directory refresh attempts return. Refresh-signal
  callbacks and sidebar evaluation rely on that ordering to avoid false
  positives from stale suppression data.
- Flyway migrations are additive. Do not drop columns/tables in deployable
  migrations.

## Proof Gates

```sh
find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check

NODE_OPTIONS=--no-warnings node --test src/test/js/sidebar-diagnostics.test.mjs

JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test

JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp -DskipTests package
```

Expected test baseline after the §37 MessageSource runtime repair: 368 green.
