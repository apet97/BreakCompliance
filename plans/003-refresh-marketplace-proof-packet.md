# Plan 003: Refresh Marketplace Proof Packet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Executor instructions**: Follow this plan step by step. Run every verification command and confirm the expected result before moving to the next step. If anything in the "STOP conditions" section occurs, stop and report. Do not improvise.
>
> **Drift check (run first)**:
>
> ```sh
> git diff --stat 2c969bb..HEAD -- \
>   docs/MARKETPLACE_SUBMISSION_CHECKLIST.md \
>   docs/LISTING.md \
>   docs/LIVE_VALIDATION.md \
>   docs/evidence
> ```
>
> If any in-scope file changed since this plan was written, compare the "Current state" excerpts against the live docs before proceeding. On a mismatch, treat it as a STOP condition.

**Goal:** Replace pending marketplace-review evidence with fresh proof for the current code SHA and current `0.2.0` sidebar/settings UI.

**Architecture:** This is a release-evidence plan, not a behavior change. First prove the local artifact, then deploy only if explicitly approved, then capture live health/manifest/install/uninstall/screenshots, and finally update the marketplace docs to say exactly what was verified and what was skipped.

**Tech Stack:** Java 21, Maven, Railway CLI, curl, jq, Clockify dev workspace, browser or Playwright for screenshots.

---

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: Plan 001 and Plan 002 if those plans will ship before marketplace submission
- **Category**: direction
- **Planned at**: commit `2c969bb`, 2026-06-13

## Why this matters

The repo is locally verified, but the marketplace packet still says same-SHA Railway deployment evidence, v0.2.0 live install/uninstall evidence, and fresh sidebar/settings screenshots are pending. Marketplace reviewers need evidence that matches the submitted code, not an older v0.1.0 install or a public endpoint observation whose deployed SHA is unknown.

## Current state

- `docs/MARKETPLACE_SUBMISSION_CHECKLIST.md` marks the key evidence gaps:

```markdown
<!-- MARKETPLACE_SUBMISSION_CHECKLIST.md:9-23 -->
| Code SHA validated | Local remediation worktree verification complete on 2026-06-13 from base `13734eb`; same-SHA Railway deployment evidence still pending |
| Live install evidence | v0.1.0 evidence present; v0.2.0 refresh pending |
| Live uninstall cleanup evidence | v0.1.0 evidence present; v0.2.0 refresh pending |
| Screenshots/videos attached | Installed screenshot present; v0.2.0 sidebar/settings screenshots pending |
| Known non-blocking follow-ups | Fresh v0.2.0 Clockify UI evidence and same-SHA Railway deploy evidence when deployment is intentionally approved |
```

- `docs/LISTING.md` lists pending screenshots:

```markdown
<!-- LISTING.md:100-110 -->
- `docs/evidence/v0.2.0-sidebar-active-findings.png` — Sidebar with
  active findings. *(Pending fresh v0.2.0 capture.)*
- `docs/evidence/v0.2.0-sidebar-preset-chooser.png` — Preset chooser
  preview cards. *(Pending fresh v0.2.0 capture.)*
- `docs/evidence/v0.2.0-structured-settings.png` — Native Clockify
  structured settings tab with the ten threshold fields. *(Pending
  fresh v0.2.0 capture.)*
```

Repo constraints to preserve:

- Do not change app behavior in this plan.
- Do not deploy unless the operator explicitly authorizes deployment.
- Do not copy secrets from `/tmp/clockify-livetest.env` into any repo file.
- If live Clockify access is unavailable, record the skip plainly instead of manufacturing evidence.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Static JS syntax | `find src/main/resources/static -name '*.js' -print0 \| xargs -0 -n1 node --check` | exit 0, no output |
| Full test suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test` | exit 0 |
| Package | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp -DskipTests package` | exit 0; `target/break-compliance-0.2.0.jar` exists |
| Current SHA | `git rev-parse --short HEAD` | prints the SHA under validation |
| Worktree check | `git status --short` | only intentional docs/evidence changes |
| Health check | `curl -fsS https://breakcompliance-production.up.railway.app/healthz` | HTTP 200 body |
| Manifest check | `curl -fsS https://breakcompliance-production.up.railway.app/manifest \| jq '.scopes'` | exactly read-only scopes |

## Scope

**In scope**:

- `docs/MARKETPLACE_SUBMISSION_CHECKLIST.md`
- `docs/LISTING.md`
- `docs/LIVE_VALIDATION.md`
- `docs/evidence/v0.2.0-sidebar-active-findings.png`
- `docs/evidence/v0.2.0-sidebar-preset-chooser.png`
- `docs/evidence/v0.2.0-structured-settings.png`
- Additional `docs/evidence/` files only if named clearly and referenced by the docs above.

**Out of scope**:

- Java source and tests.
- Manifest scopes.
- Railway variables.
- Privacy/security/data-retention policy wording unless live proof reveals a factual drift.

## Git workflow

- Branch: `codex/003-refresh-marketplace-proof-packet`
- Commit message style: `docs(marketplace): refresh submission evidence`
- Do not push or open a PR unless the operator explicitly asks.

## Steps

### Step 1: Establish the local evidence baseline

- [ ] Run:

```sh
git rev-parse --short HEAD
```

Expected: a short SHA. Record it in your notes; the plan was written at `2c969bb`, but the executor may be validating a later SHA if Plan 001 or Plan 002 landed first.

- [ ] Run:

```sh
find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check
```

Expected: exit 0, no output.

- [ ] Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test
```

Expected: exit 0. Record the final test count from the Maven summary.

- [ ] Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp -DskipTests package
```

Expected: exit 0 and `target/break-compliance-0.2.0.jar` exists.

### Step 2: Deploy only with explicit operator approval

- [ ] Ask the operator for deployment approval if it has not already been granted in the current thread.
- [ ] If approval is not granted, skip deployment and document that same-SHA live evidence remains pending.
- [ ] If approval is granted, run:

```sh
railway up --service BreakCompliance --ci
```

Expected: Railway deploy completes successfully.

- [ ] After deploy, run:

```sh
curl -fsS https://breakcompliance-production.up.railway.app/healthz
```

Expected: HTTP 200.

- [ ] Check the manifest scopes:

```sh
curl -fsS https://breakcompliance-production.up.railway.app/manifest | jq '.scopes'
```

Expected: the scopes are exactly `REPORTS_READ`, `TIME_ENTRY_READ`, and `USER_READ`; there are no `_WRITE` scopes and no `WORKSPACE_READ`.

### Step 3: Capture fresh Clockify UI screenshots

- [ ] Confirm the browser has access to the Clockify dev workspace.
- [ ] Capture these three screenshots:
  - Sidebar with active findings: `docs/evidence/v0.2.0-sidebar-active-findings.png`
  - Sidebar preset chooser: `docs/evidence/v0.2.0-sidebar-preset-chooser.png`
  - Native structured settings with the ten threshold fields: `docs/evidence/v0.2.0-structured-settings.png`
- [ ] Use Playwright or a normal browser. If using Playwright, do not put auth cookies, tokens, API keys, or workspace secrets into scripts committed to the repo.
- [ ] Before saving, visually check that screenshots do not expose secret tokens, personal email addresses beyond intended support contact, or unrelated workspace data.

**Verify**:

```sh
ls -lh docs/evidence/v0.2.0-sidebar-active-findings.png \
  docs/evidence/v0.2.0-sidebar-preset-chooser.png \
  docs/evidence/v0.2.0-structured-settings.png
```

Expected: all three files exist and are non-empty.

### Step 4: Refresh live install/uninstall evidence

- [ ] If `/tmp/clockify-livetest.env` exists, load it only in the shell:

```sh
set -a; source /tmp/clockify-livetest.env; set +a
```

Expected: no output. Do not echo variables.

- [ ] If the env file is missing, record in `docs/LIVE_VALIDATION.md` that the live Clockify validation was skipped because `/tmp/clockify-livetest.env` was unavailable.
- [ ] If live workspace credentials and operator approval are available, install the add-on in the dev workspace, trigger a refresh, confirm a webhook-driven ingest, then uninstall and verify workspace-owned rows are deleted.
- [ ] Use existing docs as the evidence format. Keep dates concrete: `2026-06-13` or the actual date of execution.

### Step 5: Update the docs to match the evidence

- [ ] Update `docs/MARKETPLACE_SUBMISSION_CHECKLIST.md`:
  - Replace pending same-SHA deploy text with the validated SHA and deploy date if deployed.
  - Replace the test count with the actual Maven summary from Step 1.
  - Mark live install/uninstall evidence as refreshed only if Step 4 actually ran.
  - Mark screenshots attached only if Step 3 produced files.
- [ ] Update `docs/LISTING.md`:
  - Remove pending markers for screenshots that now exist.
  - Keep pending markers for any screenshot not captured.
- [ ] Update `docs/LIVE_VALIDATION.md`:
  - Add a new dated section for the current validation pass.
  - Include the exact commands run and their pass/fail/skip result.
  - Do not paste secrets, tokens, API keys, full JWTs, or private workspace IDs unless the file already intentionally records a non-secret workspace label.

**Verify**:

```sh
rg -n "pending|Pending|same-SHA|304 tests|v0.1.0 evidence present" \
  docs/MARKETPLACE_SUBMISSION_CHECKLIST.md docs/LISTING.md docs/LIVE_VALIDATION.md
```

Expected: output is either empty or only describes intentionally skipped evidence with a current date and reason.

### Step 6: Final proof

- [ ] Run:

```sh
git diff --check
```

Expected: exit 0, no output.

- [ ] Run:

```sh
git status --short
```

Expected: only the intended docs/evidence files and `plans/README.md` status update are modified.

## Test plan

- Local code proof: static JS, full Maven test suite, Maven package.
- Live proof, when deployment is approved: `/healthz`, `/manifest`, Clockify install/refresh/uninstall, screenshots.
- If live proof cannot run, docs must say exactly which gate was skipped and why.

## Done criteria

- [ ] `docs/MARKETPLACE_SUBMISSION_CHECKLIST.md` no longer presents stale v0.1.0 evidence as the current v0.2.0 proof.
- [ ] `docs/LISTING.md` references actual screenshot files or explicitly marks missing screenshots pending.
- [ ] `docs/LIVE_VALIDATION.md` contains a current dated validation section with commands and outcomes.
- [ ] No secrets are committed.
- [ ] `git diff --check` exits 0.
- [ ] `plans/README.md` status row for Plan 003 is updated.

## STOP conditions

Stop and report back if:

- The operator does not approve deployment but asks for same-SHA live evidence.
- `/tmp/clockify-livetest.env` is missing and live Clockify validation is required.
- The deployed manifest contains a write scope or differs from the expected read-only scopes.
- A screenshot would expose secrets or unrelated customer/workspace data.
- Railway CLI asks for authentication or a project selection you cannot verify.

## Maintenance notes

- Release evidence drifts quickly. Use absolute dates and SHAs in docs.
- Do not delete older evidence files unless the operator asks; older screenshots can remain historical artifacts if current docs point to the fresh ones.
- If Plan 001 lands after this plan, rerun this plan's evidence steps because the deploy SHA and test count changed.
