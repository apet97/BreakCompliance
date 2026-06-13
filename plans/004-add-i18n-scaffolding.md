# Plan 004: Add I18n Scaffolding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Executor instructions**: Follow this plan step by step. Run every verification command and confirm the expected result before moving to the next step. If anything in the "STOP conditions" section occurs, stop and report. Do not improvise.
>
> **Drift check (run first)**:
>
> ```sh
> git diff --stat 2c969bb..HEAD -- \
>   src/main/java/me/apet97/breakcompliance/domain/BreakRuleEngine.java \
>   src/test/java/me/apet97/breakcompliance/domain/BreakRuleEngineTest.java \
>   src/main/java/me/apet97/breakcompliance/addon/ui/SidebarHtmlController.java \
>   src/main/resources/static/sidebar.js \
>   src/main/resources/static/sidebar \
>   src/main/resources
> ```
>
> If any in-scope file changed since this plan was written, compare the "Current state" excerpts against the live code before proceeding. On a mismatch, treat it as a STOP condition.

**Goal:** Introduce English-only i18n scaffolding so finding messages and high-visibility sidebar copy can move out of hardcoded Java/JavaScript strings without changing behavior.

**Architecture:** Use Spring `MessageSource` for backend finding messages and a small first-party JSON dictionary for sidebar copy. Ship English resources only. Do not add locale selection UI in this plan; the active locale is English, but future locales can drop in without rewriting the engine or sidebar rendering code.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring `MessageSource`, static ES modules, Maven, JUnit 5.

---

## Status

- **Priority**: P3
- **Effort**: L
- **Risk**: MED
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `2c969bb`, 2026-06-13

## Why this matters

The improvement checklist already calls out i18n scaffolding as a deferred productization task. Finding messages are persisted and exported, and the sidebar has a lot of user-facing copy embedded in Java and JavaScript. Centralizing English strings now makes future localization a resource-file change instead of a behavior refactor.

## Current state

- `docs/IMPROVEMENT_CHECKLIST.md` describes the intended direction:

```markdown
<!-- IMPROVEMENT_CHECKLIST.md:245-249 -->
- [!] **P5.3 [deferred] i18n scaffolding**
  - Route finding messages through Spring `MessageSource`; ship
    `messages_en.properties` only for now. Sidebar copy moves to
    `static/i18n/en.json`. Future locales drop in without code changes.
  - Touches: `domain/BreakRuleEngine.java`, new resource bundle.
```

- `src/main/java/me/apet97/breakcompliance/domain/BreakRuleEngine.java` currently constructs messages inline:

```java
// BreakRuleEngine.java:94-107
FindingCode.MISSING_REQUIRED_BREAK,
"Worked " + segments.workMinutes + " minutes (threshold " + active.thresholdMinutes
        + ") with no qualifying break.",
evidence));
} else if (effectiveBreakMinutes < active.requiredBreakMinutes) {
    out.add(new FindingDraft(
            input.workspaceId(),
            bucket.userId(),
            bucket.date(),
            template.getId(),
            insufficientSev,
            FindingCode.INSUFFICIENT_BREAK_DURATION,
            "Qualifying break minutes " + effectiveBreakMinutes + " below required "
                    + active.requiredBreakMinutes + ".",
```

- `src/main/java/me/apet97/breakcompliance/addon/ui/SidebarHtmlController.java` hardcodes high-visibility sidebar shell copy:

```html
<!-- SidebarHtmlController.java:46-77 -->
<div class="app-container" role="application" aria-label="Break Compliance">
  <header class="app-header">
    <h1>Break Compliance</h1>
    ...
    <span class="control-label">Active preset</span>
    ...
    <button ...>Switch…</button>
    ...
    Workspace admin required to refresh data and change settings. You can still browse existing findings.
```

- `src/main/resources/static/sidebar.js` hardcodes dynamic copy:

```js
// sidebar.js:162
btn.textContent = busy ? "Checking…" : "Check Compliance";

// sidebar.js:205
pill.textContent = `Pending refresh · webhook ${formatRelativeTime(state.pendingRefreshAt)}`;

// sidebar.js:261
node.appendChild(create("p", { className: "settings-warning-title", text: "Settings need a fix" }));
```

Repo constraints to preserve:

- Do not add a locale picker or any user-facing settings field in this plan.
- Do not change finding codes, severity, evidence shape, CSV columns, or API response shape.
- Keep `/sidebar` CSP-compatible: no inline scripts and no remote i18n fetches outside `script-src 'self'`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Static JS syntax | `find src/main/resources/static -name '*.js' -print0 \| xargs -0 -n1 node --check` | exit 0, no output |
| Focused engine test | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test -Dtest='BreakRuleEngineTest'` | exit 0 |
| Full suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test` | exit 0 |

## Scope

**In scope**:

- `src/main/resources/messages_en.properties` (create)
- `src/main/java/me/apet97/breakcompliance/domain/BreakRuleEngine.java`
- `src/test/java/me/apet97/breakcompliance/domain/BreakRuleEngineTest.java`
- `src/main/resources/static/i18n/en.json` (create)
- `src/main/resources/static/sidebar/i18n.js` (create)
- `src/main/resources/static/sidebar.js`
- `src/main/java/me/apet97/breakcompliance/addon/ui/SidebarHtmlController.java`

**Out of scope**:

- Non-English translations.
- Locale detection from Clockify claims.
- Database migrations or persisted locale preferences.
- CSV column names and finding codes.
- Broad UI redesign.

## Git workflow

- Branch: `codex/004-add-i18n-scaffolding`
- Commit message style: `feat(i18n): add english message scaffolding`
- Do not push or open a PR unless the operator explicitly asks.

## Steps

### Step 1: Add backend message resources

- [ ] Create `src/main/resources/messages_en.properties` with exactly these keys:

```properties
finding.missing_required_break=Worked {0} minutes (threshold {1}) with no qualifying break.
finding.insufficient_break_duration=Qualifying break minutes {0} below required {1}.
finding.max_continuous_work_exceeded=Continuous work {0} minutes exceeds maximum {1}.
```

- [ ] Do not add other locales in this plan.

**Verify**:

```sh
rg -n "finding\\.(missing_required_break|insufficient_break_duration|max_continuous_work_exceeded)" \
  src/main/resources/messages_en.properties
```

Expected: three matches.

### Step 2: Route engine messages through `MessageSource`

- [ ] Modify `src/main/java/me/apet97/breakcompliance/domain/BreakRuleEngine.java`.
- [ ] Add imports:

```java
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
```

- [ ] Add a field and constructors below `MAX_GAP_AS_BREAK_MINUTES`:

```java
private final MessageSource messages;

public BreakRuleEngine() {
    this(defaultMessageSource());
}

public BreakRuleEngine(MessageSource messages) {
    this.messages = Objects.requireNonNull(messages, "messages");
}

private static MessageSource defaultMessageSource() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return source;
}
```

- [ ] Add this helper near the other private helpers:

```java
private String message(String key, Object... args) {
    return messages.getMessage(key, args, Locale.ENGLISH);
}
```

- [ ] Replace the three inline strings:

```java
message("finding.missing_required_break", segments.workMinutes, active.thresholdMinutes)
```

```java
message("finding.insufficient_break_duration", effectiveBreakMinutes, active.requiredBreakMinutes)
```

```java
message("finding.max_continuous_work_exceeded",
        segments.maxContinuousWorkMinutes,
        template.getMaxContinuousWorkMinutesBeforeBreak())
```

**Verify**:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test -Dtest='BreakRuleEngineTest'
```

Expected: all existing engine tests pass.

### Step 3: Pin backend message output with tests

- [ ] In `src/test/java/me/apet97/breakcompliance/domain/BreakRuleEngineTest.java`, add or adapt assertions in existing tests so each finding code still emits the exact English message.
- [ ] If there is no focused test for one code, add one small test following the file's existing helper style.
- [ ] Assert these exact strings:
  - `Worked 300 minutes (threshold 240) with no qualifying break.`
  - `Qualifying break minutes 10 below required 15.`
  - `Continuous work 300 minutes exceeds maximum 240.`

**Verify**:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test -Dtest='BreakRuleEngineTest'
```

Expected: all `BreakRuleEngineTest` tests pass.

### Step 4: Add the sidebar English dictionary and loader

- [ ] Create directory `src/main/resources/static/i18n`.
- [ ] Create `src/main/resources/static/i18n/en.json`:

```json
{
  "app.title": "Break Compliance",
  "preset.activeLabel": "Active preset",
  "preset.switch": "Switch…",
  "admin.required": "Workspace admin required to refresh data and change settings. You can still browse existing findings.",
  "dateRange.label": "Date Range",
  "dateRange.today": "Today",
  "dateRange.thisWeek": "This Week",
  "dateRange.lastWeek": "Last Week",
  "dateRange.lastTwoWeeks": "Last 2 Weeks",
  "dateRange.lastMonth": "Last Month",
  "dateRange.allOpen": "All Open (last 90 days)",
  "dateRange.custom": "Custom Range",
  "dateRange.from": "From",
  "dateRange.to": "To",
  "action.check": "Check Compliance",
  "action.checking": "Checking…",
  "action.refresh": "Refresh",
  "action.cancel": "Cancel",
  "view.label": "View",
  "view.pivot": "Pivot Table",
  "view.checklist": "Checklist",
  "export.csv": "Export CSV",
  "loading.checking": "Checking compliance…",
  "settings.warningTitle": "Settings need a fix",
  "settings.warningFoot": "Reopen the settings page (⋯ → Settings on the add-on) to fix these, then re-run Check Compliance.",
  "status.pendingRefresh": "Pending refresh · webhook {relative}",
  "status.lastChecked": "Last checked {relative}"
}
```

- [ ] Create `src/main/resources/static/sidebar/i18n.js`:

```js
let dictionary = {};

export async function loadI18n(locale = "en") {
    const response = await fetch(`/i18n/${locale}.json`, { credentials: "same-origin" });
    if (!response.ok) {
        throw new Error(`Unable to load locale ${locale}: ${response.status}`);
    }
    dictionary = await response.json();
    return dictionary;
}

export function t(key, params = {}) {
    let value = dictionary[key] ?? key;
    for (const [name, replacement] of Object.entries(params)) {
        value = value.replaceAll(`{${name}}`, String(replacement));
    }
    return value;
}
```

**Verify**:

```sh
find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check
```

Expected: exit 0, no output.

### Step 5: Wire high-visibility sidebar shell copy to dictionary keys

- [ ] Modify `src/main/java/me/apet97/breakcompliance/addon/ui/SidebarHtmlController.java`.
- [ ] Add `data-i18n` attributes to shell elements that have dictionary keys. Example target shape:

```html
<h1 data-i18n="app.title">Break Compliance</h1>
<span class="control-label" data-i18n="preset.activeLabel">Active preset</span>
<button id="switch-preset-btn" ... data-i18n="preset.switch">Switch…</button>
<p id="admin-required-note" ...>
  <span class="admin-required-glyph" aria-hidden="true">🔒</span>
  <span data-i18n="admin.required">Workspace admin required to refresh data and change settings. You can still browse existing findings.</span>
</p>
```

- [ ] Apply the same pattern to date range labels/options, view labels, export button text, loading text, and cancel button.
- [ ] Keep existing fallback English text in the HTML so the shell remains readable if dictionary loading fails.

**Verify**:

```sh
rg -n "data-i18n" src/main/java/me/apet97/breakcompliance/addon/ui/SidebarHtmlController.java
```

Expected: at least 15 matches.

### Step 6: Load translations before rendering dynamic sidebar state

- [ ] Modify `src/main/resources/static/sidebar.js`.
- [ ] Import the loader:

```js
import { loadI18n, t } from "./sidebar/i18n.js";
```

- [ ] Add this helper near other small DOM helpers:

```js
function applyStaticTranslations(root = document) {
    root.querySelectorAll("[data-i18n]").forEach((node) => {
        const key = node.getAttribute("data-i18n");
        if (!key) return;
        node.textContent = t(key);
    });
}
```

- [ ] Replace high-visibility dynamic strings with `t(...)`, including:

```js
btn.textContent = busy ? t("action.checking") : t("action.check");
```

```js
node.textContent = t("status.lastChecked", { relative: formatRelativeTime(state.lastRunAt) });
```

```js
pill.textContent = t("status.pendingRefresh", { relative: formatRelativeTime(state.pendingRefreshAt) });
```

```js
node.appendChild(create("p", { className: "settings-warning-title", text: t("settings.warningTitle") }));
```

```js
node.appendChild(create("p", { className: "settings-warning-foot", text: t("settings.warningFoot") }));
```

- [ ] In the sidebar boot path, call `await loadI18n("en")` and `applyStaticTranslations()` before the first render. If the boot path is not already async, wrap the initialization in an async function and call it once.
- [ ] On dictionary load failure, show the existing English fallback and log a concise warning. Do not block the sidebar.

**Verify**:

```sh
find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check
```

Expected: exit 0, no output.

### Step 7: Run focused and full verification

- [ ] Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test -Dtest='BreakRuleEngineTest'
```

Expected: all engine tests pass.

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

Expected: exit 0.

## Test plan

- Backend: `BreakRuleEngineTest` asserts all three finding messages retain exact English output after moving to `MessageSource`.
- Frontend: static JS syntax check validates the new module and sidebar import.
- Full suite catches Spring bean construction issues with `BreakRuleEngine(MessageSource)`.
- Manual or browser verification is recommended after implementation: open `/sidebar` with a valid token and confirm the shell still renders English text.

## Done criteria

- [ ] `messages_en.properties` exists and contains all finding message keys.
- [ ] `BreakRuleEngine` uses `MessageSource` for finding messages.
- [ ] Existing finding message text remains unchanged.
- [ ] `static/i18n/en.json` exists.
- [ ] Sidebar imports `sidebar/i18n.js`, loads English, and translates high-visibility shell/dynamic copy.
- [ ] No locale selector or persisted locale field is added.
- [ ] Static JS check exits 0.
- [ ] Full Maven test suite exits 0.
- [ ] `plans/README.md` status row for Plan 004 is updated.

## STOP conditions

Stop and report back if:

- `BreakRuleEngine` is no longer constructed as a Spring component.
- Adding a constructor breaks tests in a way that requires broad dependency-injection rewrites.
- The sidebar boot path is too tangled to load the dictionary before first render without a broader refactor.
- The operator expects non-English translations in this plan.
- CSP changes appear necessary to load `/i18n/en.json`; this plan should work with same-origin static assets.

## Maintenance notes

- This plan intentionally ships English only. Future locale work should add locale negotiation from Clockify/user claims and additional JSON/properties files.
- Persisted historical findings keep their original message text. This plan changes message generation for future findings only.
- Reviewers should check that no user-facing API shape changed and that CSV export still includes the same `message` column values for new findings.
