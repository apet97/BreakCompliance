# Plan 002: Clean Stale Time-Off Comments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Executor instructions**: Follow this plan step by step. Run every verification command and confirm the expected result before moving to the next step. If anything in the "STOP conditions" section occurs, stop and report. Do not improvise.
>
> **Drift check (run first)**:
>
> ```sh
> git diff --stat 2c969bb..HEAD -- \
>   src/main/java/me/apet97/breakcompliance/clockify/TimeOffFetcher.java \
>   src/main/java/me/apet97/breakcompliance/config/ClockifyAddonConfig.java \
>   docs/api-calls.md \
>   CONTEXT.md \
>   AGENTS.md \
>   CLAUDE.md
> ```
>
> If any in-scope file changed since this plan was written, compare the "Current state" excerpts against the live code before proceeding. On a mismatch, treat it as a STOP condition.

**Goal:** Remove misleading active comments that still describe removed whole-day PTO suppression or the old native preset-dropdown behavior.

**Architecture:** This is a docs/comment-only cleanup. Keep the already-correct runtime behavior unchanged: approved time-off cache rows become synthetic evaluation-only `TIME_OFF` intervals, and preset selection lives in the sidebar.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Maven.

---

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `2c969bb`, 2026-06-13

## Why this matters

The code and durable docs now say partial-day approved PTO must not suppress a whole user-day. A stale Javadoc block says the opposite, which is exactly the kind of comment drift that causes future agents to reintroduce old false-positive bugs. There is also an outdated structured-settings comment that starts by describing a native preset dropdown as a loader, even though the later lines correctly say the preset chooser lives in the sidebar.

## Current state

- `src/main/java/me/apet97/breakcompliance/clockify/TimeOffFetcher.java` has stale Javadoc:

```java
// TimeOffFetcher.java:20-23
 * <p>Engine treats every (userId, date) that overlaps an APPROVED window
 * as suppressed — same effect as a Clockify {@code type=TIME_OFF} entry,
 * but works even when the workspace doesn't auto-create entries for
 * approved requests.
```

- Current durable API docs describe the correct behavior:

```markdown
<!-- docs/api-calls.md:227-231 -->
Cached approved requests are stored with their exact instants. Evaluation
converts each overlapping cache row into synthetic, non-persisted `TIME_OFF`
entries clipped to the requested UTC date range; it no longer expands approved
time off into whole suppressed user-days. That keeps partial-day PTO from
hiding real work outside the approved interval.
```

- `src/main/java/me/apet97/breakcompliance/config/ClockifyAddonConfig.java` starts with stale preset-dropdown wording:

```java
// ClockifyAddonConfig.java:123-128
// Single-tab settings: the workspace has ONE active rule template,
// always evaluated. The preset dropdown is a "load values" trigger —
// picking a preset overwrites the threshold fields with that preset's
// recommended values on save. Admins then fine-tune any individual
// field. Each subsequent save without changing the preset just
// persists the admin's manual edits.
```

- The same file later states the correct sidebar-only preset behavior:

```java
// ClockifyAddonConfig.java:136-142
// The preset chooser lives in the sidebar iframe, not here. Reason:
// Clockify's native settings UI renders each field independently
// and never re-fetches sibling fields after a change, so a
// "pick preset → thresholds populate" interaction can't be made to
// work without a full page reload. The sidebar handles preset
// selection with a real preview + confirm flow against
// POST /api/presets/apply. See sidebar.js renderPresetChooser.
```

Important boundary:

- Do not edit applied Flyway migration comments in `src/main/resources/db/migration/V13__workspace_holidays_and_time_off.sql`. Flyway validates applied migration checksums; comment-only changes can still break deployed database validation.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Compile check | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp -DskipTests compile` | exit 0 |
| Whitespace check | `git diff --check` | exit 0, no output |
| Optional full suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test` | exit 0 |

## Scope

**In scope**:

- `src/main/java/me/apet97/breakcompliance/clockify/TimeOffFetcher.java`
- `src/main/java/me/apet97/breakcompliance/config/ClockifyAddonConfig.java`

**Out of scope**:

- Runtime behavior.
- Tests, unless a comment edit accidentally reveals a compile issue.
- Flyway migration files.
- Marketplace evidence docs; Plan 003 handles release proof.

## Git workflow

- Branch: `codex/002-clean-stale-time-off-comments`
- Commit message style: `docs(core): clarify time-off and preset comments`
- Do not push or open a PR unless the operator explicitly asks.

## Steps

### Step 1: Update `TimeOffFetcher` Javadoc

- [ ] Replace the stale paragraph in `src/main/java/me/apet97/breakcompliance/clockify/TimeOffFetcher.java` with:

```java
 * <p>Approved requests are cached with exact instants. Evaluation clips each
 * overlapping interval to the requested range and appends a synthetic,
 * non-persisted {@code type=TIME_OFF} entry, so partial-day PTO does not hide
 * same-day work outside the approved interval.
```

**Verify**:

```sh
rg -n "suppressed|whole suppressed|same effect as a Clockify" \
  src/main/java/me/apet97/breakcompliance/clockify/TimeOffFetcher.java
```

Expected: no output.

### Step 2: Update the structured-settings comment

- [ ] In `src/main/java/me/apet97/breakcompliance/config/ClockifyAddonConfig.java`, replace lines 123-128 with:

```java
// Single-tab settings: the workspace has ONE active rule template,
// always evaluated. Native structured-settings owns individual threshold
// fields only. Preset selection deliberately lives in the sidebar iframe,
// where the app can preview the preset and persist all threshold changes in
// one backend transaction.
```

- [ ] Keep the later explanation about Clockify's native UI not re-fetching sibling fields.

**Verify**:

```sh
rg -n "preset dropdown|load values|without changing the preset" \
  src/main/java/me/apet97/breakcompliance/config/ClockifyAddonConfig.java
```

Expected: no output.

### Step 3: Compile and check whitespace

- [ ] Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp -DskipTests compile
```

Expected: exit 0.

- [ ] Run:

```sh
git diff --check
```

Expected: exit 0, no output.

## Test plan

- This plan changes comments only. A compile check is sufficient.
- If another plan is implemented in the same branch, use that plan's full verification gate before reporting the branch ready.

## Done criteria

- [ ] Active Java comments no longer claim approved time-off rows suppress whole user-days.
- [ ] Active Java comments no longer imply the manifest contains a preset dropdown loader.
- [ ] No Flyway migration file changed.
- [ ] Compile check exits 0.
- [ ] `git diff --check` exits 0.
- [ ] `plans/README.md` status row for Plan 002 is updated.

## STOP conditions

Stop and report back if:

- The live code has already removed or rewritten the cited comments.
- You need to edit an applied Flyway migration to complete the cleanup.
- A compile check fails for a reason unrelated to the comment changes.

## Maintenance notes

- Keep `docs/api-calls.md`, `CLAUDE.md`, `AGENTS.md`, and `CONTEXT.md` as the durable source for behavior. Comments should summarize those contracts, not fork new wording.
- If a future change intentionally alters time-off semantics, update the durable docs and tests in the same commit.
