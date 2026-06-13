// Break Compliance sidebar — Clockify add-on iframe client.
//
// Loads inside the Clockify-served iframe. On the initial top-level
// navigation Clockify cannot set headers, so the auth token arrives in
// the URL as `?auth_token=...`. We immediately read it, store it in
// module-scoped state, and strip it from the URL via History.replaceState
// (no fragment, no reload) so the token never leaks into Referer / access
// logs. Every subsequent /api/* call uses the `x-addon-token` header.
//
// The parent (Clockify) frame is reached via postMessage. Outbound messages
// follow the canonical shape documented at
// docs/clockify-marketplace/build/window-events.md: a JSON-stringified
// `{ action, payload }` object. We tighten the documented example's `"*"`
// target origin to the validated parent origin (ancestorOrigins[0] or the
// document.referrer fallback) — never `"*"`. Inbound events from Clockify
// use `{ title, body }`; the parser also tolerates the legacy
// `{ action, payload }` shape some older builds emit.
//
// This file is hand-authored ES module — no bundler, no package.json.

import { renderAuditPanel } from "./sidebar/audit-panel.js";
import { createApiClient, HttpError } from "./sidebar/api-client.js";
import { applyTheme, createMessenger, readAuthTokenFromQuery, stripAuthTokenFromUrl } from "./sidebar/auth-messenger.js";
import {
    createDateFormatters,
    DATE_PRESETS,
    enumerateDates,
    formatBreakWithSynthetic,
    formatMinutes,
    formatRelativeTime,
    severityClass,
} from "./sidebar/date-range.js";
import { clearChildren, create, el } from "./sidebar/dom.js";
import { applyFindingsCountToLastRun, diagnosticMetricRows } from "./sidebar/diagnostics.js";
import { downloadFindingsCsv as downloadFindingsCsvFile } from "./sidebar/findings-export.js";
import { displayUserName, reviewBadgeClass, reviewBadgeText, statusIconMeta } from "./sidebar/findings-rendering.js";
import { loadI18n, t } from "./sidebar/i18n.js";
import { describeIngestFailure, pollIngestionRun } from "./sidebar/ingest-polling.js";
import { fieldsThatDivergeFromPreset, PRESET_LABELS, TIMEZONE_LABELS } from "./sidebar/presets.js";
import { requestReviewNote } from "./sidebar/review-dialog.js";
import { state } from "./sidebar/state.js";

// ───────────────────────────── Constants ─────────────────────────────

const ADDON_TITLE = "Break Compliance";
const ADDON_KEY = "break-compliance-jvm";
const TOKEN_REFRESH_INTERVAL_MS = 25 * 60 * 1000;

// ────────────────────────── Module state ──────────────────────────

let addonToken = null;
let messenger = null;
const api = createApiClient(() => addonToken);

// ─────────────────── Admin-role gating ───────────────────

// True only when the session's workspaceRole resolves to a Clockify admin
// or owner. Anything else (MEMBER, missing claim, unexpected value) returns
// false — fail-closed, matching the server's RequestValidator.requireAdmin.
function isAdmin() {
    const role = String(state.session?.workspaceRole ?? "").toUpperCase();
    return role === "ADMIN" || role === "OWNER";
}

// Hide / disable every control that POSTs to an admin-gated endpoint so
// non-admins don't trigger 401/403 round-trips. Read-only surfaces (findings
// list, active-template chip, view toggle, date pickers) stay interactive.
function renderAdminGates() {
    const admin = isAdmin();
    const note = document.getElementById("admin-required-note");
    if (note) note.hidden = admin;

    const gatedButtons = [
        ["run-btn", "Workspace admin required to run a compliance check"],
        ["refresh-btn", "Workspace admin required to refresh data"],
        ["switch-preset-btn", "Workspace admin required to change presets"],
    ];
    for (const [id, title] of gatedButtons) {
        const node = document.getElementById(id);
        if (!node) continue;
        if (admin) {
            node.disabled = false;
            node.removeAttribute("aria-disabled");
            // Preserve the markup's original title (only the refresh button
            // ships with one); for the others we never wrote a title to
            // begin with, so clearing here is a no-op.
            if (id === "refresh-btn") node.title = "Re-run the last check";
            else node.removeAttribute("title");
        } else {
            node.disabled = true;
            node.setAttribute("aria-disabled", "true");
            node.title = title;
        }
    }
    // The "Reset to preset" affordance on the diverged customized-pill is
    // suppressed for non-admins inside renderCustomizedPill, which sees the
    // role check directly — no extra DOM work needed here.
}

// ─────────────────── Range/state helpers ───────────────────

function computeDateRange() {
    if (state.preset !== "custom_range") return DATE_PRESETS[state.preset]();
    if (!state.customStart || !state.customEnd) {
        return { error: "Pick both From and To dates." };
    }
    if (state.customStart > state.customEnd) {
        return { error: "From must be on or before To." };
    }
    return { start: state.customStart, end: state.customEnd };
}

function rememberFindingsRange(range, options = {}) {
    const openOnly = Boolean(options.openOnly ?? range?.openOnly);
    state.findingsRange = range?.start && range?.end
        ? { start: range.start, end: range.end, openOnly }
        : null;
}

function findingsLoadedForRange(range) {
    return Boolean(range?.start && range?.end
        && state.findingsRange?.start === range.start
        && state.findingsRange?.end === range.end
        && Boolean(state.findingsRange?.openOnly) === Boolean(range.openOnly));
}

async function loadFindingsForRange(range, options = {}) {
    const openOnly = Boolean(options.openOnly ?? range.openOnly);
    const query = { dateRangeStart: range.start, dateRangeEnd: range.end };
    if (openOnly) query.openOnly = "true";
    const body = await api("/api/findings", { query });
    state.findings = Array.isArray(body?.findings) ? body.findings : [];
    rememberFindingsRange(range, { openOnly });
    return state.findings;
}

function showBanner(kind, message = "") {
    const banner = el("status-banner");
    if (kind === "hidden") {
        banner.hidden = true;
        banner.textContent = "";
        return;
    }
    banner.hidden = false;
    banner.className = kind === "err"
        ? "error-banner"
        : kind === "warn"
            ? "warn-banner"
            : kind === "ok"
                ? "ok-banner"
                : "panel panel-body";
    banner.textContent = message;
}

function setLoading(on) {
    const node = el("loading");
    node.hidden = !on;
    if (on) node.style.display = "flex";
}

function setRunButtonState(busy) {
    const btn = el("run-btn");
    btn.disabled = busy;
    btn.textContent = busy ? t("action.checking") : t("action.check");
}

// ─────────────────── Rendering ───────────────────

function applyStaticTranslations(root = document) {
    root.querySelectorAll("[data-i18n]").forEach((node) => {
        const key = node.getAttribute("data-i18n");
        if (!key) return;
        node.textContent = t(key);
    });
}

function renderDiagnostics() {
    const node = el("diagnostics");
    clearChildren(node);
    if (!state.lastRun) {
        node.hidden = true;
        return;
    }
    node.hidden = false;
    for (const row of diagnosticMetricRows(state.lastRun)) {
        node.appendChild(create("div", undefined, [
            create("div", { className: "label", text: row.label }),
            create("div", { className: "value", text: row.value }),
        ]));
    }
}

function renderLastChecked() {
    const node = el("last-checked");
    if (!state.lastRunAt) {
        node.hidden = true;
        node.textContent = "";
        return;
    }
    node.hidden = false;
    node.textContent = t("status.lastChecked", { relative: formatRelativeTime(state.lastRunAt) });
}

function renderPendingRefreshPill() {
    const pill = document.getElementById("pending-refresh-pill");
    if (!pill) return;
    if (!state.pendingRefreshAt) {
        pill.hidden = true;
        pill.textContent = "";
        return;
    }
    pill.hidden = false;
    pill.textContent = t("status.pendingRefresh", { relative: formatRelativeTime(state.pendingRefreshAt) });
    pill.title = "Data has changed in Clockify since the last refresh. Click Refresh to pull the latest entries.";
}

// Parse the GET /api/ingest/runs/latest body into the same shape we cache
// locally after a successful Check Compliance run, so the existing
// renderLastChecked + Refresh-button code paths work unchanged. Returns
// null when the endpoint returned 204 (no completed run yet).
function applyLatestRunSnapshot(latest) {
    if (!latest || !latest.completedAt) return;
    const completedAt = new Date(latest.completedAt);
    if (isNaN(completedAt.getTime())) return;
    state.lastRunAt = completedAt;
    state.lastRunRange = {
        start: latest.dateRangeStart,
        end: latest.dateRangeEnd,
    };
    // Track diagnostics so the existing renderDiagnostics tile fills in on
    // initial paint (entriesProcessed; findingsCreated stays null until the
    // user runs a fresh evaluate — the server doesn't expose it on the run).
    state.lastRun = {
        entriesProcessed: Number(latest.entriesProcessed) || 0,
        findingsCreated: null,
    };
}

// PENDING/CLAIMED signals received after the latest completed run mean
// "Clockify told us things changed; nobody's caught up yet." Surface the
// newest such signal so admins know a refresh would actually fetch new
// data. Signals older than lastRunAt are already reflected and ignored.
function computePendingRefreshFromSignals(signals) {
    if (!Array.isArray(signals) || signals.length === 0) return null;
    const lastRunMs = state.lastRunAt instanceof Date ? state.lastRunAt.getTime() : 0;
    let newest = 0;
    for (const sig of signals) {
        if (sig.status !== "PENDING" && sig.status !== "CLAIMED") continue;
        const t = Date.parse(sig.receivedAt);
        if (!Number.isFinite(t)) continue;
        if (t <= lastRunMs) continue;
        if (t > newest) newest = t;
    }
    return newest > 0 ? new Date(newest) : null;
}

function renderValidationWarnings() {
    const node = el("settings-warning-banner");
    const warnings = Array.isArray(state.session?.validationWarnings)
        ? state.session.validationWarnings
        : [];
    if (warnings.length === 0) {
        node.hidden = true;
        clearChildren(node);
        return;
    }
    clearChildren(node);
    node.hidden = false;
    node.appendChild(create("p", { className: "settings-warning-title", text: t("settings.warningTitle") }));
    const list = create("ul", { className: "settings-warning-list" });
    for (const w of warnings) {
        const text = (w && typeof w === "object" && typeof w.message === "string")
            ? w.message
            : String(w);
        list.appendChild(create("li", { text }));
    }
    node.appendChild(list);
    node.appendChild(create("p", { className: "settings-warning-foot", text: t("settings.warningFoot") }));
}

// ─────────────────── Preset chooser ───────────────────

async function loadPresetCatalog() {
    if (state.presetCatalog) return state.presetCatalog;
    const response = await api("/api/presets");
    state.presetCatalog = response.presets ?? [];
    return state.presetCatalog;
}

function activePresetFromCatalog() {
    const key = state.session?.appliedPresetKey;
    if (!key || !state.presetCatalog) return null;
    return state.presetCatalog.find(p => p.key === key) ?? null;
}

function clearCustomizedPillInteraction(pill) {
    pill.removeAttribute("role");
    pill.removeAttribute("aria-label");
    pill.tabIndex = -1;
    pill.onclick = null;
    pill.onkeydown = null;
}

function renderCustomizedPill() {
    const pill = el("customized-pill");
    const activePreset = activePresetFromCatalog();
    const activeTemplate = state.session?.activeTemplate;
    if (!activePreset || !activeTemplate) {
        pill.hidden = true;
        clearCustomizedPillInteraction(pill);
        return;
    }
    const divergent = fieldsThatDivergeFromPreset(activeTemplate, activePreset);
    if (divergent.length === 0) {
        pill.hidden = false;
        pill.className = "customized-pill matches";
        pill.textContent = "Matches preset";
        clearCustomizedPillInteraction(pill);
    } else if (!isAdmin()) {
        // Non-admins see the informational "Customized" label without the
        // clickable Reset affordance — the apply endpoint would 403 anyway.
        pill.hidden = false;
        pill.className = "customized-pill diverged";
        pill.textContent = "Customized";
        clearCustomizedPillInteraction(pill);
    } else {
        pill.hidden = false;
        pill.className = "customized-pill diverged";
        pill.textContent = `Customized · Reset to ${activePreset.label}?`;
        pill.setAttribute("role", "button");
        pill.setAttribute("aria-label", `Reset customized thresholds to ${activePreset.label}`);
        pill.tabIndex = 0;
        const resetToPreset = () => applyPreset(activePreset.key, { skipDivergenceConfirm: true });
        pill.onclick = resetToPreset;
        pill.onkeydown = event => {
            if (event.key !== "Enter" && event.key !== " ") return;
            event.preventDefault();
            resetToPreset();
        };
    }
}

async function togglePresetChooser(force) {
    state.chooserOpen = typeof force === "boolean" ? force : !state.chooserOpen;
    const panel = el("preset-chooser");
    const trigger = el("switch-preset-btn");
    trigger.setAttribute("aria-expanded", state.chooserOpen ? "true" : "false");
    if (!state.chooserOpen) {
        panel.hidden = true;
        return;
    }
    panel.hidden = false;
    try {
        await loadPresetCatalog();
    } catch (err) {
        clearChildren(panel);
        panel.appendChild(create("p", { className: "muted", text: "Couldn't load presets — try again." }));
        return;
    }
    renderPresetChooser();
}

function renderPresetChooser() {
    const panel = el("preset-chooser");
    if (!state.chooserOpen) { panel.hidden = true; return; }
    clearChildren(panel);
    const activeKey = state.session?.appliedPresetKey;
    const activeTemplate = state.session?.activeTemplate;
    for (const preset of state.presetCatalog ?? []) {
        const card = create("div", { className: "preset-card" + (preset.key === activeKey ? " active" : "") });
        const header = create("div", { className: "preset-card-header" });
        header.appendChild(create("span", { className: "preset-card-title", text: preset.label }));
        if (preset.key === activeKey) {
            const divergent = fieldsThatDivergeFromPreset(activeTemplate, preset);
            const badge = create("span", {
                className: "preset-card-badge " + (divergent.length === 0 ? "matches" : "diverged"),
                text: divergent.length === 0 ? "Active · matches" : `Active · customized (${divergent.length})`,
            });
            header.appendChild(badge);
        }
        card.appendChild(header);

        const t = preset.thresholds;
        const summary = create("ul", { className: "preset-card-summary" });
        summary.appendChild(create("li", { text: `Work threshold: ${formatMinutes(t.workThresholdMinutes)}` }));
        summary.appendChild(create("li", { text: `Required break: ${formatMinutes(t.breakThresholdMinutes)}` }));
        summary.appendChild(create("li", { text: `Min segment: ${formatMinutes(t.minBreakSegmentMinutes)}` }));
        if (t.secondWorkThresholdMinutes) {
            summary.appendChild(create("li", { text: `2nd tier: ${formatMinutes(t.secondWorkThresholdMinutes)} → ${formatMinutes(t.secondBreakThresholdMinutes)}` }));
        }
        summary.appendChild(create("li", { text: `Split breaks: ${t.allowSplitBreaks ? "allowed" : "one block required"}` }));
        card.appendChild(summary);

        if (preset.description) {
            card.appendChild(create("p", { className: "preset-card-desc", text: preset.description }));
        }

        const applyBtn = create("button", {
            className: "btn-secondary preset-card-apply",
            text: preset.key === activeKey ? "Re-apply" : "Apply this preset",
        });
        applyBtn.type = "button";
        applyBtn.disabled = state.chooserBusy === preset.key;
        if (state.chooserBusy === preset.key) applyBtn.textContent = "Applying…";
        applyBtn.addEventListener("click", () => applyPreset(preset.key));
        card.appendChild(applyBtn);

        panel.appendChild(card);
    }
}

async function applyPreset(presetKey, options = {}) {
    if (!isAdmin()) return;
    const activePreset = activePresetFromCatalog();
    const target = (state.presetCatalog ?? []).find(p => p.key === presetKey);
    const activeTemplate = state.session?.activeTemplate;
    if (!target) return;

    // Confirm if applying would overwrite user customizations. Skip the
    // prompt when the user explicitly chose "Reset to preset" via the pill.
    if (!options.skipDivergenceConfirm && activePreset && activeTemplate) {
        const divergent = fieldsThatDivergeFromPreset(activeTemplate, activePreset);
        if (divergent.length > 0) {
            const ok = window.confirm(
                `Applying "${target.label}" will overwrite ${divergent.length} customized field${divergent.length === 1 ? "" : "s"}. Continue?`);
            if (!ok) return;
        }
    }

    state.chooserBusy = presetKey;
    renderPresetChooser();
    try {
        await api("/api/presets/apply", {
            method: "POST",
            body: JSON.stringify({ presetKey }),
        });
        // Re-fetch the session so the chip + popover + customized pill
        // reflect the new threshold values.
        state.session = await api("/api/session");
        renderActiveTemplate();
        renderCustomizedPill();
        renderValidationWarnings();
        renderSettingsHint();
        showBanner("ok", `Applied "${target.label}". Reload the Clockify settings page if you want to fine-tune individual fields.`);
        togglePresetChooser(false);
    } catch (err) {
        showBanner("err", err instanceof HttpError
            ? `Couldn't apply preset: ${err.message}`
            : "Couldn't apply preset.");
    } finally {
        state.chooserBusy = null;
        renderPresetChooser();
    }
}

function renderActiveTemplate() {
    const labelNode = el("active-template-label");
    const chip = el("active-template-chip");
    const details = el("active-template-details");

    const presetKey = state.session?.appliedPresetKey ?? "custom-basic";
    // Prefer the catalog's label so any future preset addition shows up
    // correctly without a sidebar.js update. Fall back to the static map.
    const catalogEntry = (state.presetCatalog ?? []).find(p => p.key === presetKey);
    const presetLabel = catalogEntry?.label ?? PRESET_LABELS[presetKey] ?? presetKey;
    labelNode.textContent = presetLabel;

    clearChildren(details);
    const active = state.session?.activeTemplate;
    if (!active) {
        details.appendChild(create("p", { className: "muted", text: "Thresholds not configured yet — open the settings page in Clockify." }));
        chip.removeAttribute("data-preview");
        return;
    }

    // P2.5 — one-line summary on chip hover, so admins don't have to click
    // the chip to glance at the current rule.
    // P2.7 — also mirror onto aria-label so screen-reader users get the
    // same context (CSS ::after pseudo-content is invisible to AT).
    const splitNote = active.allowSplitBreaks ? "split allowed" : "single block required";
    const segmentNote = `${active.minBreakSegmentMinutes ?? 0}m segments OK`;
    const preview = `≥${active.workThresholdMinutes ?? 0}m work → ≥${active.breakThresholdMinutes ?? 0}m break (${segmentNote}, ${splitNote})`;
    chip.setAttribute("data-preview", preview);
    chip.setAttribute("aria-label", `Active preset: ${presetLabel}. ${preview}. Click to see full thresholds.`);

    const rows = [
        ["Work threshold", formatMinutes(active.workThresholdMinutes)],
        ["Required break", formatMinutes(active.breakThresholdMinutes)],
        ["Min break segment", formatMinutes(active.minBreakSegmentMinutes)],
        ["Max continuous work", formatMinutes(active.maxContinuousWorkMinutes)],
        ["Grace period", formatMinutes(active.gracePeriodMinutes)],
        ["Split breaks", active.allowSplitBreaks
            ? "Allowed (sum of qualifying segments)"
            : "Not allowed (single uninterrupted break required — California meal-rule)"],
    ];
    if (active.secondWorkThresholdMinutes && active.secondWorkThresholdMinutes > 0) {
        rows.push(["Second-tier work threshold", formatMinutes(active.secondWorkThresholdMinutes)]);
        rows.push(["Second-tier required break", formatMinutes(active.secondBreakThresholdMinutes)]);
    } else {
        rows.push(["Second tier", "Disabled"]);
    }
    rows.push(["Timezone strategy", TIMEZONE_LABELS[active.timezoneStrategy] ?? (active.timezoneStrategy ?? "—")]);
    rows.push(["Fallback detection", active.fallbackDetectionEnabled ? "On" : "Off"]);

    const dl = create("dl", { className: "threshold-list" });
    for (const [k, v] of rows) {
        dl.appendChild(create("dt", { text: k }));
        dl.appendChild(create("dd", { text: v }));
    }
    details.appendChild(dl);
    chip.setAttribute("aria-expanded", state.detailsOpen ? "true" : "false");
    details.hidden = !state.detailsOpen;
}

function toggleActiveTemplateDetails(force) {
    state.detailsOpen = typeof force === "boolean" ? force : !state.detailsOpen;
    renderActiveTemplate();
}

function pickWorstSeverityFinding(findings) {
    const rank = { VIOLATION: 3, WARNING: 2, INFO: 1 };
    return findings.reduce((worst, current) =>
        rank[current.severity] > rank[worst.severity] ? current : worst, findings[0]);
}

function renderExportButton() {
    const btn = document.getElementById("export-csv-btn");
    if (!btn) return;
    btn.hidden = state.findings.length === 0;
}

// Build the export URL from the same range the findings list used. Falling
// back to state.lastRunRange means a freshly-opened sidebar (seeded from
// /api/ingest/runs/latest by the staleness wiring) can also export without
// requiring a Check Compliance click in this tab first.
function currentFindingsRange() {
    if (state.lastRunRange?.start && state.lastRunRange?.end) {
        return { start: state.lastRunRange.start, end: state.lastRunRange.end };
    }
    const computed = computeDateRange();
    if ("error" in computed) return null;
    return computed;
}

function renderAuditLog() {
    const root = document.getElementById("audit-panel");
    if (!root) return;
    renderAuditPanel(root, {
        entries: state.audit.entries,
        loaded: state.audit.loaded,
        loading: state.audit.loading,
        range: state.audit.range ?? currentFindingsRange(),
        isAdmin: isAdmin(),
        onRefresh: () => loadAuditLog(),
    });
}

async function loadAuditLog() {
    const range = currentFindingsRange();
    if (!range) {
        showBanner("err", "Pick a date range before loading the audit log.");
        return;
    }
    state.audit.loading = true;
    state.audit.range = range;
    renderAuditLog();
    try {
        const body = await api("/api/audit", {
            query: {
                dateRangeStart: range.start,
                dateRangeEnd: range.end,
                limit: "50",
            },
        });
        state.audit.entries = Array.isArray(body?.audit) ? body.audit : [];
        state.audit.loaded = true;
        state.audit.range = range;
    } catch (err) {
        showBanner("err", err instanceof HttpError
            ? `Audit log failed: ${err.message}`
            : "Audit log failed.");
    } finally {
        state.audit.loading = false;
        renderAuditLog();
    }
}

async function downloadFindingsCsv() {
    await downloadFindingsCsvFile({
        range: currentFindingsRange(),
        token: addonToken,
        showBanner,
    });
}

function renderResults() {
    const container = el("results-container");
    clearChildren(container);
    renderExportButton();
    renderUserFilter();
    if (state.findings.length === 0) {
        if (state.lastRunRange?.openOnly && findingsLoadedForRange(state.lastRunRange)) {
            container.appendChild(create("div", { className: "empty-state ok" }, [
                create("p", { className: "empty-title", text: "No open findings." }),
                create("p", { className: "empty-detail", text: "The backlog view has no unreviewed break-compliance findings in this range." }),
            ]));
        } else if (state.lastRun && findingsLoadedForRange(state.lastRunRange)) {
            // Successful run, just no violations — celebrate the empty list.
            container.appendChild(create("div", { className: "empty-state ok" }, [
                create("p", { className: "empty-title", text: "All clear in this range." }),
                create("p", { className: "empty-detail", text: `Checked ${state.lastRun.entriesProcessed} time ${state.lastRun.entriesProcessed === 1 ? "entry" : "entries"} — no break-compliance issues found.` }),
            ]));
        } else if (state.lastRun) {
            container.appendChild(create("div", { className: "empty-state" }, [
                create("p", { className: "empty-title", text: "Latest check is available." }),
                create("p", { className: "empty-detail", text: "Findings for that range have not loaded yet. Refresh to reload the latest compliance results." }),
            ]));
        } else {
            container.appendChild(create("div", { className: "empty-state" }, [
                create("p", { className: "empty-title", text: "No check has run yet." }),
                create("p", { className: "empty-detail", text: "Pick a date range and click Check Compliance to evaluate the workspace against the active template." }),
            ]));
        }
        return;
    }
    if (state.view === "pivot") renderPivot(container);
    else renderChecklist(container);
}

// P2.1 — populate the user-filter dropdown from the userIds currently in
// state.findings. Hidden when there are no findings or only one user.
// Client-side filter via state.userFilter; downstream renderers respect it.
function renderUserFilter() {
    const sel = document.getElementById("user-filter-select");
    if (!sel) return;
    // Single pass: prefer the first non-blank userName per user, else
    // fall through to the raw id. Avoids the O(N×U) filter-per-user trap.
    const byUserId = new Map();
    for (const f of state.findings) {
        if (!f.userId) continue;
        const existing = byUserId.get(f.userId);
        const name = f.userName && f.userName.trim();
        if (existing == null) {
            byUserId.set(f.userId, name || f.userId);
        } else if (existing === f.userId && name) {
            byUserId.set(f.userId, name);
        }
    }
    if (byUserId.size < 2) {
        sel.hidden = true;
        state.userFilter = null;
        return;
    }
    sel.hidden = false;
    clearChildren(sel);
    const all = create("option", { text: `All users (${byUserId.size})` });
    all.value = "";
    sel.appendChild(all);
    for (const [userId, name] of [...byUserId.entries()].sort((a, b) => a[1].localeCompare(b[1]))) {
        const opt = create("option", { text: name });
        opt.value = userId;
        sel.appendChild(opt);
    }
    sel.value = state.userFilter ?? "";
}

function visibleFindings() {
    // P2.1 — apply the user-filter dropdown selection. Empty / null
    // selection = no filter; show everyone.
    if (!state.userFilter) return state.findings;
    return state.findings.filter(f => f.userId === state.userFilter);
}

function renderPivot(container) {
    const findingsView = visibleFindings();
    const byUser = new Map();
    for (const f of findingsView) {
        if (!byUser.has(f.userId)) byUser.set(f.userId, new Map());
        const byDate = byUser.get(f.userId);
        if (!byDate.has(f.date)) byDate.set(f.date, []);
        byDate.get(f.date).push(f);
    }

    // Show every day in the run's range (not just days with findings) so an
    // 8h block of green cells reads as "this user was checked and passed",
    // not "the table is missing days". Fall back to the union of finding
    // dates if no range is recorded (defensive).
    let sortedDates = state.lastRunRange
        ? enumerateDates(state.lastRunRange.start, state.lastRunRange.end)
        : [];
    if (sortedDates.length === 0) {
        const fromFindings = new Set();
        for (const f of state.findings) fromFindings.add(f.date);
        sortedDates = [...fromFindings].sort();
    }

    // Pre-compute display names so each row picks the best available name.
    const namesByUser = new Map();
    for (const [userId, byDate] of byUser) {
        const allFindings = [...byDate.values()].flat();
        namesByUser.set(userId, displayUserName(allFindings, userId));
    }

    const scroll = create("div", { className: "pivot-scroll-container" });
    const table = create("table", { className: "pivot-table" });
    const thead = create("thead");
    const headerRow = create("tr");
    headerRow.appendChild(create("th", { className: "user-col", text: "User" }));
    const { weekday, monthDay } = createDateFormatters(state.session?.userTimeZone);
    for (const date of sortedDates) {
        // Anchor the calendar date at noon UTC so DST transitions in the
        // user's timezone can't shift the rendered date by a day.
        const d = new Date(`${date}T12:00:00Z`);
        const dayName = weekday.format(d);
        const dayDate = monthDay.format(d);
        const th = create("th", { className: "day-col", title: date });
        th.appendChild(create("div", { className: "day-name", text: dayName }));
        th.appendChild(create("div", { className: "day-date", text: dayDate }));
        headerRow.appendChild(th);
    }
    thead.appendChild(headerRow);
    table.appendChild(thead);

    const tbody = create("tbody");
    for (const [userId, byDate] of byUser) {
        const row = create("tr");
        const display = namesByUser.get(userId) ?? userId;
        row.appendChild(create("td", { className: "user-col", title: userId, text: display }));
        for (const date of sortedDates) {
            const findings = byDate.get(date) ?? [];
            if (findings.length === 0) {
                // No findings for this user-day. Could be a clean pass, a
                // PTO/holiday day (engine filters TIME_OFF/HOLIDAY), or no
                // entries recorded. "·" + a tooltip explains.
                const cell = create("td", {
                    className: "day-cell status-none",
                    title: `${date} — no findings`,
                });
                cell.appendChild(create("span", { className: "sr-only", text: "No findings" }));
                cell.appendChild(create("span", { className: "status-icon", text: "·" }));
                row.appendChild(cell);
                continue;
            }
            const worst = pickWorstSeverityFinding(findings);
            const cls = severityClass(worst.severity);
            const { icon, srLabel } = statusIconMeta(worst.severity);
            const summary = `${formatMinutes(worst.evidence.workMinutes)} · ${formatBreakWithSynthetic(worst.evidence)}`;
            const cell = create("td", { className: `day-cell status-${cls}`, title: worst.message });
            cell.appendChild(create("span", { className: "sr-only", text: `${srLabel}: ` }));
            cell.appendChild(create("span", { className: "status-icon", text: icon }));
            cell.appendChild(create("div", { className: "cell-detail", text: summary }));
            row.appendChild(cell);
        }
        tbody.appendChild(row);
    }
    table.appendChild(tbody);
    scroll.appendChild(table);
    container.appendChild(scroll);
}

// Cycle a finding through OPEN -> ACKNOWLEDGED -> OVERRIDDEN -> OPEN.
// Non-OPEN transitions can carry an optional audit note captured through
// the first-party review dialog module.
async function cycleReview(findingId, currentStatus) {
    const next = currentStatus === "ACKNOWLEDGED" ? "OVERRIDDEN"
        : currentStatus === "OVERRIDDEN" ? "OPEN"
        : "ACKNOWLEDGED";
    let note = null;
    if (next !== "OPEN") {
        const entered = await requestReviewNote(next);
        if (entered === undefined) return;
        note = entered == null ? null : entered.trim();
    }
    const body = note ? { status: next, note } : { status: next };
    try {
        const review = await api(`/api/findings/${encodeURIComponent(findingId)}/review`, {
            method: "POST",
            body: JSON.stringify(body),
        });
        // Patch the in-memory finding so the next renderResults reflects
        // the new state without a full /api/findings round-trip.
        for (const f of state.findings) {
            if (f.id === findingId) {
                f.review = next === "OPEN" ? null : {
                    findingId: review.findingId,
                    status: review.status,
                    note: review.note,
                    updatedAt: review.updatedAt,
                };
                break;
            }
        }
        renderResults();
        if (state.audit.loaded) loadAuditLog();
    } catch (err) {
        showBanner("err", err instanceof HttpError
            ? `Couldn't update review: ${err.message}`
            : "Couldn't update review.");
    }
}

function renderChecklist(container) {
    const findingsView = visibleFindings();
    const byUser = new Map();
    const admin = isAdmin();
    for (const f of findingsView) {
        if (!byUser.has(f.userId)) byUser.set(f.userId, new Map());
        const byDate = byUser.get(f.userId);
        if (!byDate.has(f.date)) byDate.set(f.date, []);
        byDate.get(f.date).push(f);
    }
    if (byUser.size === 0) {
        container.appendChild(create("p", { className: "no-data", text: "No findings." }));
        return;
    }
    const list = create("div", { className: "checklist-container" });
    for (const [userId, byDate] of byUser) {
        const card = create("div", { className: "user-card" });
        const allFindings = [...byDate.values()].flat();
        const display = displayUserName(allFindings, userId);
        card.appendChild(create("div", { className: "user-name", title: userId, text: display }));
        const days = create("div", { className: "day-list" });
        for (const date of [...byDate.keys()].sort()) {
            const findings = byDate.get(date);
            const worst = pickWorstSeverityFinding(findings);
            const cls = severityClass(worst.severity);
            const { icon, srLabel } = statusIconMeta(worst.severity);
            const summary = `Work: ${formatMinutes(worst.evidence.workMinutes)} | Break: ${formatBreakWithSynthetic(worst.evidence)}`;
            const section = create("div", { className: `day-section status-${cls}` });
            const header = create("div", { className: "day-header" });
            header.appendChild(create("span", { className: "sr-only", text: `${srLabel}: ` }));
            header.appendChild(create("span", { className: "day-status-icon", text: icon }));
            header.appendChild(create("span", { className: "day-label", text: date }));
            header.appendChild(create("span", { className: "day-summary", text: summary }));
            section.appendChild(header);
            const items = create("ul", { className: "rule-list" });
            for (const f of findings) {
                const itemCls = severityClass(f.severity);
                const itemIcon = itemCls === "pass" ? "✓" : itemCls === "warn" ? "!" : "✗";
                const reviewStatus = f.review?.status ?? "OPEN";
                const liClasses = ["rule-item"];
                if (reviewStatus !== "OPEN") liClasses.push("rule-item--reviewed");
                const li = create("li", { className: liClasses.join(" ") });
                li.appendChild(create("span", { className: `rule-icon status-${itemCls}`, text: itemIcon }));
                li.appendChild(create("span", { className: "rule-name", text: f.code }));
                li.appendChild(create("span", { className: "rule-detail", text: f.message }));
                const badgeText = reviewBadgeText(reviewStatus);
                if (badgeText) {
                    const badge = create("span", {
                        className: reviewBadgeClass(reviewStatus),
                        text: badgeText,
                        title: f.review?.note ? `Note: ${f.review.note}` : "No note recorded",
                    });
                    li.appendChild(badge);
                }
                const reviewBtn = create("button", {
                    className: "btn-link rule-review-btn",
                    text: reviewStatus === "OPEN" ? "Mark…"
                        : reviewStatus === "ACKNOWLEDGED" ? "→ Override"
                        : "→ Re-open",
                    title: "Cycle this finding's review state (admin only)",
                });
                reviewBtn.type = "button";
                reviewBtn.disabled = !admin;
                if (!admin) reviewBtn.title = "Workspace admin required to review findings";
                reviewBtn.addEventListener("click", () => {
                    if (!admin) return;
                    cycleReview(f.id, reviewStatus);
                });
                li.appendChild(reviewBtn);

                // P2.2 — drill-down: surface entryIds + runningEntriesSkipped
                // + overnightShifts already in the evidence payload. No new
                // endpoint needed.
                const entryIds = Array.isArray(f.evidence?.entryIds) ? f.evidence.entryIds : [];
                const runningSkipped = Number(f.evidence?.runningEntriesSkipped) || 0;
                const overnightShifts = Number(f.evidence?.overnightShifts) || 0;
                if (entryIds.length > 0 || runningSkipped > 0 || overnightShifts > 0) {
                    const drillBtn = create("button", {
                        className: "btn-link rule-drill-btn",
                        text: `▾ ${entryIds.length} ${entryIds.length === 1 ? "entry" : "entries"}`
                            + (runningSkipped > 0 ? ` + ${runningSkipped} running` : ""),
                        title: "Show contributing time-entry ids",
                    });
                    drillBtn.type = "button";
                    drillBtn.setAttribute("aria-expanded", "false");
                    // P2.7 — screen-reader friendly label so the button
                    // announces its target clearly.
                    drillBtn.setAttribute("aria-label",
                        `Show ${entryIds.length} contributing time-entry id${entryIds.length === 1 ? "" : "s"} for this finding`);
                    const drillBody = create("div", { className: "rule-drill-body" });
                    drillBody.hidden = true;
                    if (entryIds.length > 0) {
                        const idList = create("ul", { className: "rule-drill-ids" });
                        for (const id of entryIds) {
                            idList.appendChild(create("li", { className: "rule-drill-id", text: id }));
                        }
                        drillBody.appendChild(idList);
                    }
                    if (runningSkipped > 0) {
                        drillBody.appendChild(create("p", {
                            className: "rule-drill-note",
                            // P1.5 — admins refresh after the user stops the timer.
                            text: `${runningSkipped} running timer${runningSkipped === 1 ? "" : "s"} skipped — refresh once the user stops the entry to include it in the evaluation.`,
                        }));
                    }
                    if (overnightShifts > 0) {
                        drillBody.appendChild(create("p", {
                            className: "rule-drill-note",
                            // P1.4 — flag that the work-minutes here include
                            // the tail of an overnight shift, so admins can
                            // double-check before acting.
                            text: `${overnightShifts} entr${overnightShifts === 1 ? "y" : "ies"} crossed midnight — the full shift is attributed to the start-day; review before acting.`,
                        }));
                    }
                    drillBtn.addEventListener("click", () => {
                        const open = drillBody.hidden;
                        drillBody.hidden = !open;
                        drillBtn.setAttribute("aria-expanded", open ? "true" : "false");
                        drillBtn.firstChild.nodeValue = (open ? "▴ " : "▾ ")
                            + `${entryIds.length} ${entryIds.length === 1 ? "entry" : "entries"}`
                            + (runningSkipped > 0 ? ` + ${runningSkipped} running` : "");
                    });
                    li.appendChild(drillBtn);
                    li.appendChild(drillBody);
                }
                items.appendChild(li);
            }
            section.appendChild(items);
            days.appendChild(section);
        }
        card.appendChild(days);
        list.appendChild(card);
    }
    container.appendChild(list);
}

// ─────────────────── Main flow: Check Compliance ───────────────────

function setLoadingMessage(text) {
    const node = el("loading");
    if (!node) return;
    // Loading element layout: <spinner /><span>...</span>. Update the
    // span (the second child) so we keep the spinner glyph intact.
    const span = node.querySelector("span");
    if (span) span.textContent = text;
}

// P2.8 — read-only fetch path for the "All open" preset. Reuses the
// findings list endpoint with openOnly=true; no ingest, no evaluate.
async function loadOpenFindings(range) {
    showBanner("hidden");
    setLoading(true);
    setLoadingMessage(`Loading open findings ${range.start} → ${range.end}…`);
    try {
        await loadFindingsForRange(range, { openOnly: true });
    } catch (err) {
        setLoading(false);
        showBanner("err", err instanceof HttpError
            ? `Loading open findings failed: ${err.message}`
            : "Loading open findings failed.");
        return;
    }
    state.lastRunRange = { start: range.start, end: range.end, openOnly: true };
    state.audit.loaded = false;
    state.audit.entries = [];
    state.audit.range = range;
    setLoading(false);
    renderResults();
    renderAuditLog();
    if (state.findings.length === 0) {
        showBanner("ok", `No open findings in the last 90 days.`);
    } else {
        showBanner("warn",
            `${state.findings.length} open finding(s) — acknowledge or override to clear the backlog.`);
    }
}

async function runCompliance() {
    showBanner("hidden");
    const range = computeDateRange();
    if ("error" in range) {
        showBanner("err", range.error);
        return;
    }
    // P2.8 — "All open" preset is a read-only backlog view. Skip the
    // 90-day re-ingest + re-evaluate (expensive + unwanted) and just
    // refresh the rendered list from persisted findings.
    if (range.openOnly) {
        await loadOpenFindings(range);
        return;
    }
    setRunButtonState(true);
    setLoading(true);
    setLoadingMessage(`Fetching ${range.start} → ${range.end} from Clockify…`);
    state.cancelIngest = false;

    let runId;
    try {
        const startResponse = await api("/api/ingest/detailed-report", {
            method: "POST",
            body: JSON.stringify({ dateRangeStart: range.start, dateRangeEnd: range.end }),
        });
        runId = startResponse.run.id;
    } catch (err) {
        if (err instanceof HttpError) {
            const code = String(err.body?.error ?? err.message);
            if (err.status === 409 && code === "ingest_in_progress" && err.body?.existingRunId) {
                runId = String(err.body.existingRunId);
                setLoadingMessage(`Waiting for existing check ${range.start} → ${range.end}…`);
            } else if (err.status === 503 && code === "installation_inactive") {
                setLoading(false);
                setRunButtonState(false);
                showBanner("err", err.body?.message
                    ?? "This workspace's add-on is currently inactive.");
                return;
            } else if (err.status === 503 && code === "installation_not_found") {
                setLoading(false);
                setRunButtonState(false);
                showBanner("err",
                    "Add-on not installed for this workspace yet. Re-install from the marketplace and try again.");
                return;
            } else {
                setLoading(false);
                setRunButtonState(false);
                showBanner("err",
                    `Could not start ingestion: ${code}.`);
                return;
            }
        }
        if (!runId) {
            setLoading(false);
            setRunButtonState(false);
            showBanner("err", "Unexpected ingestion failure.");
            return;
        }
    }

    let runFinal;
    try {
        runFinal = await pollIngestionRun({
            api,
            runId,
            range,
            isCanceled: () => state.cancelIngest,
            setLoadingMessage,
        });
    } catch (err) {
        setLoading(false);
        setRunButtonState(false);
        if (err && err.canceled) {
            showBanner("warn", "Check canceled. The ingestion will keep running in the background — refresh later to pick up the results.");
            return;
        }
        showBanner("err", err instanceof HttpError
            ? `Lost contact with the ingestion run: ${err.message}`
            : "Lost contact with the ingestion run.");
        return;
    }

    if (runFinal.status === "FAILED") {
        setLoading(false);
        setRunButtonState(false);
        showBanner("warn", describeIngestFailure(runFinal.errorCode));
        return;
    }
    const entriesProcessed = Number(runFinal.entriesProcessed) || 0;
    setLoadingMessage("Evaluating compliance…");

    let evalResult;
    try {
        evalResult = await api("/api/findings/evaluate", {
            method: "POST",
            body: JSON.stringify({ dateRangeStart: range.start, dateRangeEnd: range.end }),
        });
    } catch (err) {
        setLoading(false);
        setRunButtonState(false);
        showBanner("warn", err instanceof HttpError
            ? `Evaluation failed: ${err.message}. Findings unchanged.`
            : "Evaluation failed.");
        return;
    }

    try {
        await loadFindingsForRange(range, { openOnly: range.openOnly });
    } catch (err) {
        setLoading(false);
        setRunButtonState(false);
        showBanner("err", err instanceof HttpError
            ? `Loading findings failed: ${err.message}`
            : "Loading findings failed.");
        return;
    }

    state.lastRun = { entriesProcessed, findingsCreated: evalResult.findingsCreated };
    state.lastRunAt = new Date();
    state.lastRunRange = { start: range.start, end: range.end, openOnly: Boolean(range.openOnly) };
    state.audit.loaded = false;
    state.audit.entries = [];
    state.audit.range = range;
    // Fresh data just landed — any prior PENDING/CLAIMED webhook signals
    // are either consumed by this run or older than it, so the pill drops.
    state.pendingRefreshAt = null;
    setRunButtonState(false);
    setLoading(false);
    renderDiagnostics();
    renderLastChecked();
    renderPendingRefreshPill();
    renderResults();
    renderAuditLog();
    if (state.findings.length === 0) {
        showBanner("ok", `Range ${range.start} → ${range.end}: no break-compliance issues.`);
    } else {
        showBanner("warn", `Range ${range.start} → ${range.end}: ${state.findings.length} finding(s).`);
    }
}

// ─────────────────── Form sync + initial loads ───────────────────

function updateFormFromState() {
    el("date-preset-select").value = state.preset;
    el("custom-range-inputs").hidden = state.preset !== "custom_range";
}

function renderSettingsHint() {
    const fallback = el("settings-hint-fallback");
    if (fallback) {
        fallback.hidden = false;
    }
}

async function loadInitialData() {
    const statusNode = el("session-status");
    let latestFindingsLoadFailed = false;
    try {
        // Parallel: session info + preset catalog. Both are needed before
        // the active-template chip can render its label and the
        // Matches/Customized pill can decide which state to show.
        // 204 from /api/ingest/runs/latest returns null body via api(); a
        // 4xx/5xx on either staleness query is non-fatal — degrade to "no
        // staleness indicator" rather than block the whole sidebar boot on
        // a transient backend failure.
        const [session, , latestRun, signals] = await Promise.all([
            api("/api/session"),
            loadPresetCatalog().catch(() => null),
            api("/api/ingest/runs/latest").catch(() => null),
            api("/api/refresh-signals").catch(() => null),
        ]);
        state.session = session;
        applyLatestRunSnapshot(latestRun);
        if (state.lastRunRange) {
            try {
                await loadFindingsForRange(state.lastRunRange);
                applyFindingsCountToLastRun(state);
            } catch (err) {
                state.findings = [];
                rememberFindingsRange(null);
                latestFindingsLoadFailed = true;
            }
        }
        state.pendingRefreshAt = computePendingRefreshFromSignals(signals);
        statusNode.hidden = true;
        statusNode.textContent = "";
        renderActiveTemplate();
        renderCustomizedPill();
        renderValidationWarnings();
        renderSettingsHint();
        renderAdminGates();
        renderLastChecked();
        renderPendingRefreshPill();
        renderDiagnostics();
        renderResults(); // first-paint empty state
        renderAuditLog();
        if (latestFindingsLoadFailed) {
            showBanner("warn", "Latest check loaded, but findings could not be loaded. Click Refresh to retry.");
        }
    } catch (err) {
        statusNode.hidden = false;
        statusNode.textContent = "Not connected — try reloading the addon.";
        if (err instanceof HttpError && (err.status === 401 || err.status === 403)) {
            showBanner("err", "Session expired — try reloading the addon.");
        } else {
            showBanner("err", err instanceof HttpError ? `Session error: ${err.message}` : "Session error.");
        }
        return;
    }
    updateFormFromState();
}

// ─────────────────── Event wiring ───────────────────

function wireEvents() {
    el("date-preset-select").addEventListener("change", e => {
        state.preset = e.target.value;
        updateFormFromState();
    });
    el("custom-start-date").addEventListener("change", e => { state.customStart = e.target.value; });
    el("custom-end-date").addEventListener("change", e => { state.customEnd = e.target.value; });
    el("run-btn").addEventListener("click", () => {
        if (!isAdmin()) return;
        runCompliance();
    });
    el("cancel-ingest-btn").addEventListener("click", () => { state.cancelIngest = true; });

    // Refresh button — re-run the last range, or fall through to the active
    // preset if no run has happened yet.
    el("refresh-btn").addEventListener("click", () => {
        if (!isAdmin()) return;
        // Also refresh the session info so the active-template chip picks
        // up any threshold/preset change from the structured-settings page.
        api("/api/session")
            .then(s => { state.session = s; renderActiveTemplate(); renderCustomizedPill(); renderValidationWarnings(); renderSettingsHint(); })
            .catch(() => { /* non-fatal — runCompliance will surface its own errors */ });
        if (state.lastRunRange?.openOnly) {
            void loadOpenFindings(state.lastRunRange);
            return;
        }
        if (state.lastRunRange) {
            state.preset = "custom_range";
            state.customStart = state.lastRunRange.start;
            state.customEnd = state.lastRunRange.end;
            updateFormFromState();
        }
        runCompliance();
    });

    // Active-template chip → toggle the thresholds popover.
    el("active-template-chip").addEventListener("click", () => toggleActiveTemplateDetails());
    // Switch-preset button → toggle the inline chooser panel.
    el("switch-preset-btn").addEventListener("click", () => {
        if (!isAdmin()) return;
        togglePresetChooser();
    });
    // Click outside the chip dismisses the popover.
    document.addEventListener("click", e => {
        if (!state.detailsOpen) return;
        const chip = document.getElementById("active-template-chip");
        const details = document.getElementById("active-template-details");
        if (chip && details && !chip.contains(e.target) && !details.contains(e.target)) {
            toggleActiveTemplateDetails(false);
        }
    });
    document.addEventListener("keydown", e => {
        if (e.key === "Escape" && state.detailsOpen) {
            toggleActiveTemplateDetails(false);
            // Return focus to the chip so keyboard users keep their place
            // instead of falling back to <body>.
            const chip = document.getElementById("active-template-chip");
            chip?.focus?.();
        }
    });

    const exportBtn = document.getElementById("export-csv-btn");
    if (exportBtn) exportBtn.addEventListener("click", () => { downloadFindingsCsv(); });

    for (const radio of document.querySelectorAll('input[name="view-toggle"]')) {
        radio.addEventListener("change", () => {
            state.view = radio.value;
            renderResults();
        });
    }

    // P2.1 — sidebar user-filter dropdown.
    const userSelect = document.getElementById("user-filter-select");
    if (userSelect) {
        userSelect.addEventListener("change", () => {
            state.userFilter = userSelect.value || null;
            renderResults();
        });
    }
}

// Refresh the "Last checked" relative time once a minute so the label
// doesn't go stale while the sidebar is open. The interval pauses when
// the iframe is hidden (Clockify switches to another sidebar entry, or
// the browser tab is backgrounded) so we don't burn CPU on a non-visible
// surface; on re-show, an immediate refresh corrects any drift that
// accumulated while we were paused.
let lastCheckedTicker = null;
function startLastCheckedTicker() {
    if (lastCheckedTicker != null) return; // idempotent
    lastCheckedTicker = setInterval(() => {
        if (state.lastRunAt) renderLastChecked();
        if (state.pendingRefreshAt) renderPendingRefreshPill();
    }, 30 * 1000);
}
function stopLastCheckedTicker() {
    if (lastCheckedTicker != null) {
        clearInterval(lastCheckedTicker);
        lastCheckedTicker = null;
    }
}
function wireVisibilityChange() {
    document.addEventListener("visibilitychange", () => {
        if (document.hidden) {
            stopLastCheckedTicker();
        } else {
            // Coming back from hidden — fresh-render once, then resume.
            if (state.lastRunAt) renderLastChecked();
            if (state.pendingRefreshAt) renderPendingRefreshPill();
            startLastCheckedTicker();
        }
    });
}

// Settings navigation removed. Clockify's documented `navigate` postMessage
// only supports a fixed set of locations (per canonical
// docs/clockify-marketplace/build/window-events.md, currently just "tracker"),
// not arbitrary paths. Our previous external-link workaround was making things
// worse: on developer.clockify.me, the URL pattern needs the catalog addon
// id (which the iframe's JWT claims don't carry — claims.addonId is the
// per-workspace installation id, a different identifier), so admins landed
// on an "addon unavailable" page. A static caption in the sidebar header
// now tells admins where to click in Clockify's own UI:
//   Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings

// ─────────────────── Boot ───────────────────

function initAuthAndMessenger() {
    const token = readAuthTokenFromQuery();
    if (token) addonToken = token;
    stripAuthTokenFromUrl();
    messenger = createMessenger();

    const theme = (new URLSearchParams(window.location.search).get("theme") ?? "").toUpperCase();
    if (theme === "DARK" || theme === "LIGHT") applyTheme(theme);

    messenger.on("URL_CHANGED", () => {
        const urlToken = readAuthTokenFromQuery();
        if (urlToken) {
            addonToken = urlToken;
            stripAuthTokenFromUrl();
        }
    });

    // P2.6 — Clockify dispatches THEME_CHANGED when the user toggles light
    // ↔ dark in their own UI. Mirror the change live instead of waiting
    // for an iframe reload.
    messenger.on("THEME_CHANGED", (payload) => {
        const next = String(payload?.theme ?? payload ?? "").toUpperCase();
        if (next === "DARK" || next === "LIGHT") applyTheme(next);
    });

    setInterval(() => {
        messenger.refreshAddonToken();
    }, TOKEN_REFRESH_INTERVAL_MS);
}

async function init() {
    document.title = ADDON_TITLE;
    try {
        await loadI18n("en");
        document.title = t("app.title");
        applyStaticTranslations();
    } catch (err) {
        console.warn("i18n.load.failed", err);
    }
    wireEvents();
    wireVisibilityChange();
    startLastCheckedTicker();
    loadInitialData();
}

initAuthAndMessenger();
if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => { void init(); });
} else {
    void init();
}
