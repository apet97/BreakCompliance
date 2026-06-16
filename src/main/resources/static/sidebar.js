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
// This file is the orchestrator: auth/messenger boot, the Check-Compliance
// flow, banners/diagnostics/staleness, audit wiring, and event wiring. The
// findings views (triage / pivot / checklist) and the preset surface live in
// their own modules under sidebar/views/. This file is hand-authored ES
// module — no bundler, no package.json.

import { renderAuditPanel } from "./sidebar/audit-panel.js";
import { createApiClient, HttpError } from "./sidebar/api-client.js";
import { applyTheme, createMessenger, readAuthTokenFromQuery, stripAuthTokenFromUrl } from "./sidebar/auth-messenger.js";
import { DATE_PRESETS, formatRelativeTime } from "./sidebar/date-range.js";
import { clearChildren, create, el } from "./sidebar/dom.js";
import { applyFindingsCountToLastRun, diagnosticMetricRows } from "./sidebar/diagnostics.js";
import { downloadFindingsCsv as downloadFindingsCsvFile } from "./sidebar/findings-export.js";
import { loadI18n, t } from "./sidebar/i18n.js";
import { describeIngestFailure, pollIngestionRun } from "./sidebar/ingest-polling.js";
import { requestReviewNote } from "./sidebar/review-dialog.js";
import { isAdmin } from "./sidebar/roles.js";
import { state } from "./sidebar/state.js";
import {
    configurePresetUi,
    loadPresetCatalog,
    renderActiveTemplate,
    renderCustomizedPill,
    toggleActiveTemplateDetails,
    togglePresetChooser,
} from "./sidebar/views/preset-ui.js";
import { renderChecklist } from "./sidebar/views/checklist.js";
import { renderPivot } from "./sidebar/views/pivot.js";
import { renderTriage } from "./sidebar/views/triage.js";

// ───────────────────────────── Constants ─────────────────────────────

const ADDON_TITLE = "Break Compliance";
const TOKEN_REFRESH_INTERVAL_MS = 25 * 60 * 1000;

// ────────────────────────── Module state ──────────────────────────

let addonToken = null;
let messenger = null;
const api = createApiClient(() => addonToken);

// ─────────────────── Admin-role gating ───────────────────

// Hide / disable every control that POSTs to an admin-gated endpoint so
// non-admins don't trigger 401/403 round-trips. Read-only surfaces (findings
// list, active-template chip, view toggle, date pickers) stay interactive.
// The admin check itself lives in sidebar/roles.js (fail-closed).
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
        return {
            start: state.lastRunRange.start,
            end: state.lastRunRange.end,
            openOnly: Boolean(state.lastRunRange.openOnly),
        };
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
    const range = currentFindingsRange();
    await downloadFindingsCsvFile({
        range: range ? { ...range, userId: state.userFilter } : null,
        token: addonToken,
        showBanner,
    });
}

function renderResults() {
    const container = el("results-container");
    clearChildren(container);
    // Drop a stale user filter that points at someone no longer present in the
    // current findings (e.g. after a range change) so a hidden filter can't
    // silently empty the views. Done here, once — not as a side effect buried
    // inside renderUserFilter.
    if (state.userFilter && !state.findings.some(f => f.userId === state.userFilter)) {
        state.userFilter = null;
    }
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
    if (state.view === "triage") renderTriage(container, { submitReview, rerender: renderResults });
    else if (state.view === "pivot") renderPivot(container);
    else renderChecklist(container, { submitReview });
}

// P2.1 — populate the user-filter dropdown from the userIds currently in
// state.findings. Hidden when there are no findings or only one user. Pure
// render: the filter value itself is reconciled in renderResults.
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

// Apply an explicit review transition. Non-OPEN transitions can carry an
// optional audit note captured through the first-party review dialog module.
// Fail-closed on non-admins (the server gates the endpoint too). Shared by the
// triage FindingCard (explicit Ack/Override/Undo) and the checklist (cycle).
async function submitReview(findingId, next) {
    if (!isAdmin()) return;
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

// Re-render the validation-warning banner + settings hint after a preset
// apply. Handed to the preset module via configurePresetUi so it can refresh
// these orchestrator-owned surfaces without importing back into this file.
function afterPresetApplied() {
    renderValidationWarnings();
    renderSettingsHint();
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
    // Give the preset module its cross-cutting dependencies before any load.
    configurePresetUi({ api, showBanner, afterApply: afterPresetApplied });
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
