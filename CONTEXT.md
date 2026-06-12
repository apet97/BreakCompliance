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
- `RuleTemplate`, `TemplateAssignment`, and `GroupMembership` tables remain for
  backward-compatible deletion and historical data, but current engine input
  does not use them.
- Holidays and approved time off are cached suppression data. Workspace-wide
  holidays suppress every user's bucket for that date; user-specific holidays
  and time off suppress only matching users. Group-only holidays are skipped
  until group membership expansion exists.

## Key Runtime Invariants

- `backendUrl` and `reportsUrl` come from verified JWT claims or persisted install
  state. Production outbound Clockify URLs must be HTTPS `*.clockify.me`;
  localhost requires the explicit dev/test opt-in property.
- `/api/*` accepts `X-Addon-Token` only. `/sidebar` may accept `?auth_token=`
  for initial iframe navigation and then scrubs it from the URL.
- `script-src 'self'` is intentional. `/sidebar` must not render inline scripts;
  first-paint theme bootstrap lives in `/theme-init.js`.
- Only one RUNNING ingest may exist for the same `(workspaceId, dateRange)`; V15
  enforces this with a partial unique index and startup cleanup.
- Flyway migrations are additive. Do not drop columns/tables in deployable
  migrations.

## Proof Gates

```sh
find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check

JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test

JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp -DskipTests package
```

Expected test baseline after the §30 improvement pass: 339 green.
