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

// ───────────────────────────── Constants ─────────────────────────────

const ADDON_TITLE = "Break Compliance";
const ADDON_KEY = "break-compliance-jvm";
const QUERY_PARAM_AUTH_TOKEN = "auth_token";
const TOKEN_REFRESH_INTERVAL_MS = 25 * 60 * 1000;

const DATE_PRESETS = {
    today: () => dayRange(0),
    this_week: () => weekRange(0),
    last_week: () => weekRange(-1),
    last_2_weeks: () => weekRange(-2, 0),
    last_month: () => monthRange(-1),
};

// Fallback labels when /api/presets hasn't loaded yet. The catalog is the
// source of truth — these are only used during the first paint before
// loadPresetCatalog resolves.
const PRESET_LABELS = {
    "custom-basic": "Custom (Editable Defaults)",
    "california-style": "California (IWC Meal & Rest)",
    "germany-arbzg-style": "Germany (ArbZG §3 & §4)",
};

const TIMEZONE_LABELS = {
    "ENTRY_TIMEZONE": "Entry timezone",
    "UTC": "UTC",
};

// ────────────────────────── Module state ──────────────────────────

let addonToken = null;
let messenger = null;

const state = {
    session: null,
    jurisdiction: "custom-basic",
    preset: "this_week",
    customStart: "",
    customEnd: "",
    view: "pivot",
    findings: [],
    lastRun: null,
    lastRunAt: null,        // Date — when the last successful Check Compliance completed
    lastRunRange: null,     // { start, end } — used by the refresh button
    detailsOpen: false,     // active-template details popover visibility
    cancelIngest: false,    // flips true when the user clicks Cancel mid-poll
    presetCatalog: null,    // [{key,label,description,thresholds:{...}}] from GET /api/presets
    chooserOpen: false,     // preset-chooser panel visibility
    chooserBusy: null,      // presetKey currently being applied, or null
};

// ────────────────────────── Errors ──────────────────────────

class HttpError extends Error {
    constructor(status, body, message) {
        super(message ?? `HTTP ${status}`);
        this.status = status;
        this.body = body;
    }
}

// ─────────────────── URL / origin / auth-token helpers ───────────────────

function safeOrigin(url) {
    if (!url || !url.trim()) return null;
    try {
        const parsed = new URL(url);
        if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
        return parsed.origin;
    } catch {
        return null;
    }
}

function ancestorOriginOf(location) {
    if (!location?.ancestorOrigins) return null;
    if (Array.isArray(location.ancestorOrigins)) {
        return typeof location.ancestorOrigins[0] === "string"
            ? safeOrigin(location.ancestorOrigins[0])
            : null;
    }
    if (location.ancestorOrigins.length === 0) return null;
    const first = location.ancestorOrigins.item(0);
    return typeof first === "string" ? safeOrigin(first) : null;
}

function locationToUrl(location) {
    if (location?.href) return new URL(location.href);
    const origin = location?.origin ?? "https://addon.invalid";
    const pathname = location?.pathname ?? "/";
    const search = location?.search ?? "";
    const hash = location?.hash ?? "";
    return new URL(`${origin}${pathname}${search}${hash}`);
}

function readAuthTokenFromQuery() {
    const search = window.location?.search ?? "";
    return new URLSearchParams(search).get(QUERY_PARAM_AUTH_TOKEN);
}

function stripAuthTokenFromUrl() {
    const url = locationToUrl(window.location);
    const token = url.searchParams.get(QUERY_PARAM_AUTH_TOKEN);
    if (!token) return { stripped: false, token: null };
    url.searchParams.delete(QUERY_PARAM_AUTH_TOKEN);
    window.history?.replaceState?.(null, "", url.toString());
    return { stripped: true, token };
}

function parentOriginFrom(location, document) {
    const fromAncestor = ancestorOriginOf(location);
    if (fromAncestor) return fromAncestor;
    const referrer = document?.referrer ?? "";
    return referrer ? safeOrigin(referrer) : null;
}

// ─────────────────── postMessage bridge to parent (Clockify) ───────────────────

function parseIncomingMessage(raw) {
    const data = typeof raw === "string"
        ? (() => { try { return JSON.parse(raw); } catch { return null; } })()
        : raw;
    if (!data || typeof data !== "object") return null;
    const title = typeof data.title === "string"
        ? data.title
        : (typeof data.action === "string" ? data.action : null);
    if (!title) return null;
    const body = Object.hasOwn(data, "body") ? data.body : data.payload;
    return { title, body };
}

function createMessenger() {
    const parentOrigin = parentOriginFrom(window.location, window.document);
    const target = window.top ?? null;
    const listeners = new Map();

    function onMessage(event) {
        if (!parentOrigin || event.origin !== parentOrigin) return;
        const parsed = parseIncomingMessage(event.data);
        if (!parsed) return;
        listeners.get(parsed.title)?.forEach(fn => fn(parsed, event));
    }
    window.addEventListener("message", onMessage);

    function dispatch(action, payload) {
        if (!parentOrigin || !target?.postMessage) return false;
        // Canonical outbound shape per docs/clockify-marketplace/build/
        // window-events.md: JSON-stringified `{ action, payload }`. Strict
        // target origin (not "*").
        target.postMessage(
            JSON.stringify({ action, payload: payload ?? {} }),
            parentOrigin);
        return true;
    }

    return {
        parentOrigin,
        destroy() { window.removeEventListener("message", onMessage); listeners.clear(); },
        dispatch,
        navigate(path) { return dispatch("navigate", path); },
        on(title, fn) {
            let set = listeners.get(title);
            if (!set) { set = new Set(); listeners.set(title, set); }
            set.add(fn);
            return () => set?.delete(fn);
        },
        off(title, fn) { listeners.get(title)?.delete(fn); },
        preview() { return dispatch("preview"); },
        refreshAddonToken() { return dispatch("refreshAddonToken"); },
        toastrPop(payload) { return dispatch("toastrPop", payload); },
    };
}

// ─────────────────── Theme ───────────────────

function applyTheme(theme) {
    // The inline <head> script already set data-clockify-theme on
    // <html> synchronously to avoid a paint flash. Here we just mirror
    // the value onto <body> for any CSS that keys off the body element.
    const target = document.body ?? document.documentElement;
    if (!target) return;
    const resolved = theme === "DARK" ? "dark" : "light";
    target.classList.toggle("dark", resolved === "dark");
    target.setAttribute("data-clockify-theme", resolved);
}

// ─────────────────── API client ───────────────────

function buildApiUrl(path, query) {
    const url = new URL(path, window.location.origin);
    if (query) {
        for (const [k, v] of Object.entries(query)) {
            if (v != null && v !== "") url.searchParams.set(k, v);
        }
    }
    return url.pathname + (url.search ? `?${url.searchParams.toString()}` : "");
}

async function api(path, options = {}) {
    if (!addonToken) throw new HttpError(401, { error: "token_missing" });
    const { query, ...rest } = options;
    const headers = new Headers(rest.headers);
    headers.set("x-addon-token", addonToken);
    if (rest.body && !headers.has("content-type")) headers.set("content-type", "application/json");

    const response = await fetch(buildApiUrl(path, query), { ...rest, headers });
    const text = await response.text();
    let body = null;
    if (text.length > 0) {
        try { body = JSON.parse(text); } catch { body = text; }
    }
    if (!response.ok) {
        const message = (body && typeof body === "object" && "error" in body)
            ? String(body.error)
            : `HTTP ${response.status}`;
        throw new HttpError(response.status, body, message);
    }
    return body;
}

// ─────────────────── Date preset helpers ───────────────────

function pad2(n) { return n.toString().padStart(2, "0"); }

function isoDate(d) {
    return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`;
}

function dayRange(offsetDays) {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + offsetDays);
    const iso = isoDate(d);
    return { start: iso, end: iso };
}

function weekRange(weekOffset, endWeekOffset) {
    const now = new Date();
    const dayOfWeekMonZero = (now.getUTCDay() + 6) % 7;
    const start = new Date(now);
    start.setUTCDate(now.getUTCDate() - dayOfWeekMonZero + weekOffset * 7);
    const endOffset = endWeekOffset ?? weekOffset;
    const end = new Date(now);
    end.setUTCDate(now.getUTCDate() - dayOfWeekMonZero + endOffset * 7 + 6);
    return { start: isoDate(start), end: isoDate(end) };
}

function monthRange(monthOffset) {
    const now = new Date();
    const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + monthOffset, 1));
    const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + monthOffset + 1, 0));
    return { start: isoDate(start), end: isoDate(end) };
}

function formatMinutes(minutes) {
    if (minutes == null || minutes <= 0) return "0m";
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    if (h === 0) return `${m}m`;
    if (m === 0) return `${h}h`;
    return `${h}h ${m}m`;
}

function formatBreakWithSynthetic(evidence) {
    const total = evidence?.breakMinutes ?? 0;
    const synth = evidence?.syntheticBreakMinutes ?? 0;
    if (!synth) return formatMinutes(total);
    return `${formatMinutes(total)} · ${formatMinutes(synth)} detected`;
}

function formatRelativeTime(date) {
    if (!(date instanceof Date) || isNaN(date.getTime())) return "";
    const diffSec = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
    if (diffSec < 5) return "just now";
    if (diffSec < 60) return `${diffSec}s ago`;
    const diffMin = Math.round(diffSec / 60);
    if (diffMin < 60) return `${diffMin} min${diffMin === 1 ? "" : "s"} ago`;
    const diffHour = Math.round(diffMin / 60);
    if (diffHour < 24) return `${diffHour}h ago`;
    const diffDay = Math.round(diffHour / 24);
    return `${diffDay}d ago`;
}

function severityClass(severity) {
    if (severity === "INFO") return "pass";
    if (severity === "WARNING") return "warn";
    return "fail";
}

// ─────────────────── DOM helpers ───────────────────

function el(id) {
    const node = document.getElementById(id);
    if (!node) throw new Error(`expected #${id} in sidebar markup`);
    return node;
}

function clearChildren(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
}

function create(tag, opts, children) {
    const node = document.createElement(tag);
    if (opts?.className) node.className = opts.className;
    if (opts?.title) node.title = opts.title;
    if (opts?.href && tag === "a") node.href = opts.href;
    if (opts?.text !== undefined) node.textContent = opts.text;
    if (children) {
        for (const child of children) {
            node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
        }
    }
    return node;
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
    btn.textContent = busy ? "Checking…" : "Check Compliance";
}

// ─────────────────── Rendering ───────────────────

function renderDiagnostics() {
    const node = el("diagnostics");
    clearChildren(node);
    if (!state.lastRun) {
        node.hidden = true;
        return;
    }
    node.hidden = false;
    node.appendChild(create("div", undefined, [
        create("div", { className: "label", text: "Entries ingested" }),
        create("div", { className: "value", text: String(state.lastRun.entriesProcessed) }),
    ]));
    node.appendChild(create("div", undefined, [
        create("div", { className: "label", text: "Findings created" }),
        create("div", { className: "value", text: String(state.lastRun.findingsCreated) }),
    ]));
}

function renderLastChecked() {
    const node = el("last-checked");
    if (!state.lastRunAt) {
        node.hidden = true;
        node.textContent = "";
        return;
    }
    node.hidden = false;
    node.textContent = `Last checked ${formatRelativeTime(state.lastRunAt)}`;
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
    node.appendChild(create("p", { className: "settings-warning-title", text: "Settings need a fix" }));
    const list = create("ul", { className: "settings-warning-list" });
    for (const w of warnings) {
        const text = (w && typeof w === "object" && typeof w.message === "string")
            ? w.message
            : String(w);
        list.appendChild(create("li", { text }));
    }
    node.appendChild(list);
    node.appendChild(create("p", { className: "settings-warning-foot", text: "Reopen the settings page (⋯ → Settings on the add-on) to fix these, then re-run Check Compliance." }));
}

// ─────────────────── Preset chooser ───────────────────

// Compare current active-template values against a preset's canonical
// thresholds. Returns the list of field names that differ — empty list
// means "matches preset". Null fields on either side are treated as
// "not set" (which matches itself).
function fieldsThatDivergeFromPreset(active, preset) {
    if (!active || !preset?.thresholds) return [];
    const t = preset.thresholds;
    const diff = [];
    const compare = (name, a, b) => { if ((a ?? null) !== (b ?? null)) diff.push(name); };
    compare("workThresholdMinutes",       active.workThresholdMinutes,       t.workThresholdMinutes);
    compare("breakThresholdMinutes",      active.breakThresholdMinutes,      t.breakThresholdMinutes);
    compare("minBreakSegmentMinutes",     active.minBreakSegmentMinutes,     t.minBreakSegmentMinutes);
    compare("maxContinuousWorkMinutes",   active.maxContinuousWorkMinutes,   t.maxContinuousWorkMinutes);
    compare("gracePeriodMinutes",         active.gracePeriodMinutes,         t.gracePeriodMinutes);
    compare("allowSplitBreaks",           active.allowSplitBreaks,           t.allowSplitBreaks);
    // Preset 2nd-tier columns can be null (custom-basic). DB columns hold
    // 0 instead. Treat 0 and null as equivalent for "Matches preset".
    const z = v => (v == null || v === 0) ? null : v;
    compare("secondWorkThresholdMinutes",  z(active.secondWorkThresholdMinutes),  z(t.secondWorkThresholdMinutes));
    compare("secondBreakThresholdMinutes", z(active.secondBreakThresholdMinutes), z(t.secondBreakThresholdMinutes));
    return diff;
}

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

function renderCustomizedPill() {
    const pill = el("customized-pill");
    const activePreset = activePresetFromCatalog();
    const activeTemplate = state.session?.activeTemplate;
    if (!activePreset || !activeTemplate) {
        pill.hidden = true;
        return;
    }
    const divergent = fieldsThatDivergeFromPreset(activeTemplate, activePreset);
    if (divergent.length === 0) {
        pill.hidden = false;
        pill.className = "customized-pill matches";
        pill.textContent = "Matches preset";
        pill.removeAttribute("role");
        pill.onclick = null;
    } else {
        pill.hidden = false;
        pill.className = "customized-pill diverged";
        pill.textContent = `Customized · Reset to ${activePreset.label}?`;
        pill.setAttribute("role", "button");
        pill.onclick = () => applyPreset(activePreset.key, { skipDivergenceConfirm: true });
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
        renderSettingsLink();
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
        return;
    }

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

function renderResults() {
    const container = el("results-container");
    clearChildren(container);
    if (state.findings.length === 0) {
        if (state.lastRun) {
            // Successful run, just no violations — celebrate the empty list.
            container.appendChild(create("div", { className: "empty-state ok" }, [
                create("p", { className: "empty-title", text: "All clear in this range." }),
                create("p", { className: "empty-detail", text: `Checked ${state.lastRun.entriesProcessed} time ${state.lastRun.entriesProcessed === 1 ? "entry" : "entries"} — no break-compliance issues found.` }),
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

// Picks the best display name for a user from any of their findings.
// `userName` was added in §21; pre-§21 findings still have null userName,
// so we fall through to userId in that case.
function displayUserName(findings, userId) {
    for (const f of findings) {
        if (f.userName && typeof f.userName === "string" && f.userName.trim().length > 0) {
            return f.userName;
        }
    }
    return userId;
}

function enumerateDates(startIso, endIso) {
    // Inclusive day-by-day enumeration. Returns ISO strings.
    const out = [];
    if (!startIso || !endIso) return out;
    const start = new Date(`${startIso}T00:00:00Z`);
    const end = new Date(`${endIso}T00:00:00Z`);
    if (isNaN(start.getTime()) || isNaN(end.getTime())) return out;
    // Cap defensively at ~6 weeks so a bad input can't blow up the DOM.
    const MAX_DAYS = 45;
    let count = 0;
    for (let d = start; d <= end && count < MAX_DAYS; d.setUTCDate(d.getUTCDate() + 1)) {
        out.push(`${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`);
        count++;
    }
    return out;
}

// Locale-aware day-of-week + short-date formatters. Built once per render
// pass off state.session.userTimeZone (or the browser default when the
// JWT didn't carry one) so the pivot table reads naturally for admins in
// any region — e.g. "Mon 12/05" in en-US vs "Lun 12/05" in fr-FR vs
// "12月5日 月" in ja-JP. Intl handles the fallback to system locale when
// the timezone is missing or invalid.
function dateFormatters() {
    const tz = state.session?.userTimeZone || undefined;
    const safe = (opts) => {
        try {
            return new Intl.DateTimeFormat(undefined, { ...opts, timeZone: tz });
        } catch {
            // Bad tz string from a malformed claim — fall back to system tz.
            return new Intl.DateTimeFormat(undefined, opts);
        }
    };
    return {
        weekday: safe({ weekday: "short" }),
        monthDay: safe({ month: "numeric", day: "numeric" }),
    };
}

function statusIconMeta(severity) {
    const cls = severityClass(severity);
    if (cls === "pass") return { icon: "✓", srLabel: "Pass" };
    if (cls === "warn") return { icon: "!", srLabel: "Warning" };
    return { icon: "✗", srLabel: "Violation" };
}

function renderPivot(container) {
    const byUser = new Map();
    for (const f of state.findings) {
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
    const { weekday, monthDay } = dateFormatters();
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

function renderChecklist(container) {
    const byUser = new Map();
    for (const f of state.findings) {
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
                const li = create("li", { className: "rule-item" });
                li.appendChild(create("span", { className: `rule-icon status-${itemCls}`, text: itemIcon }));
                li.appendChild(create("span", { className: "rule-name", text: f.code }));
                li.appendChild(create("span", { className: "rule-detail", text: f.message }));
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

// Poll the async ingest run until it reaches a terminal state (COMPLETED or
// FAILED). Returns the final IngestionRun body. Throws on poll-level errors
// (network / 4xx). Cancellation is cooperative — the caller flips
// state.cancelIngest and the loop exits the next tick.
async function pollIngestionRun(runId, range, isCanceled) {
    let delay = 800;
    const maxDelay = 4000;
    while (true) {
        if (isCanceled?.()) {
            const err = new Error("canceled");
            err.canceled = true;
            throw err;
        }
        const body = await api(`/api/ingest/runs/${encodeURIComponent(runId)}`);
        if (body.status === "COMPLETED" || body.status === "FAILED") return body;
        if (body.status === "RUNNING") {
            const processed = Number(body.entriesProcessed) || 0;
            setLoadingMessage(processed > 0
                ? `Imported ${processed.toLocaleString()} entries…`
                : `Fetching ${range.start} → ${range.end} from Clockify…`);
        }
        await new Promise(r => setTimeout(r, delay));
        delay = Math.min(maxDelay, Math.round(delay * 1.4));
    }
}

function describeIngestFailure(errorCode) {
    if (!errorCode) return "Ingestion failed. The run was recorded — re-open the addon and try again.";
    if (errorCode === "ClockifyApi:401") {
        return "Reports API is unavailable in this workspace. Install in a production Clockify workspace to run full compliance checks.";
    }
    if (errorCode.startsWith("ClockifyApi:")) {
        return `Clockify rejected the report request (${errorCode}). Try again in a minute; if it persists, re-open the addon to refresh credentials.`;
    }
    return `Ingestion failed (${errorCode}). The run was recorded so an admin can audit it from Settings.`;
}

async function runCompliance() {
    showBanner("hidden");
    const range = computeDateRange();
    if ("error" in range) {
        showBanner("err", range.error);
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
        setLoading(false);
        setRunButtonState(false);
        if (err instanceof HttpError) {
            const code = String(err.body?.error ?? err.message);
            if (err.status === 503 && code === "installation_inactive") {
                showBanner("err", err.body?.message
                    ?? "This workspace's add-on is currently inactive.");
            } else if (err.status === 503 && code === "installation_not_found") {
                showBanner("err",
                    "Add-on not installed for this workspace yet. Re-install from the marketplace and try again.");
            } else {
                showBanner("err",
                    `Could not start ingestion: ${code}.`);
            }
            return;
        }
        showBanner("err", "Unexpected ingestion failure.");
        return;
    }

    let runFinal;
    try {
        runFinal = await pollIngestionRun(runId, range, () => state.cancelIngest);
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

    let listResult;
    try {
        listResult = await api("/api/findings", {
            query: { dateRangeStart: range.start, dateRangeEnd: range.end },
        });
    } catch (err) {
        setLoading(false);
        setRunButtonState(false);
        showBanner("err", err instanceof HttpError
            ? `Loading findings failed: ${err.message}`
            : "Loading findings failed.");
        return;
    }

    state.findings = listResult.findings;
    state.lastRun = { entriesProcessed, findingsCreated: evalResult.findingsCreated };
    state.lastRunAt = new Date();
    state.lastRunRange = { start: range.start, end: range.end };
    setRunButtonState(false);
    setLoading(false);
    renderDiagnostics();
    renderLastChecked();
    renderResults();
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

function renderSettingsLink() {
    const link = el("open-settings-link");
    const fallback = el("settings-hint-fallback");
    const url = state.session?.settingsUrl;
    if (typeof url === "string" && /^https:\/\//.test(url)) {
        link.href = url;
        link.hidden = false;
        fallback.hidden = true;
    } else {
        // No URL in /api/session (claims missing backendUrl, etc.) — keep
        // the collapsible breadcrumb so the user still has guidance.
        link.hidden = true;
        fallback.hidden = false;
    }
}

async function loadInitialData() {
    const statusNode = el("session-status");
    try {
        // Parallel: session info + preset catalog. Both are needed before
        // the active-template chip can render its label and the
        // Matches/Customized pill can decide which state to show.
        const [session] = await Promise.all([
            api("/api/session"),
            loadPresetCatalog().catch(() => null),
        ]);
        state.session = session;
        statusNode.hidden = true;
        statusNode.textContent = "";
        renderActiveTemplate();
        renderCustomizedPill();
        renderValidationWarnings();
        renderSettingsLink();
        renderResults(); // first-paint empty state
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
    el("run-btn").addEventListener("click", () => { runCompliance(); });
    el("cancel-ingest-btn").addEventListener("click", () => { state.cancelIngest = true; });

    // Refresh button — re-run the last range, or fall through to the active
    // preset if no run has happened yet.
    el("refresh-btn").addEventListener("click", () => {
        if (state.lastRunRange) {
            state.preset = "custom_range";
            state.customStart = state.lastRunRange.start;
            state.customEnd = state.lastRunRange.end;
            updateFormFromState();
        }
        // Also refresh the session info so the active-template chip picks
        // up any threshold/preset change from the structured-settings page.
        api("/api/session")
            .then(s => { state.session = s; renderActiveTemplate(); renderCustomizedPill(); renderValidationWarnings(); renderSettingsLink(); })
            .catch(() => { /* non-fatal — runCompliance will surface its own errors */ });
        runCompliance();
    });

    // Active-template chip → toggle the thresholds popover.
    el("active-template-chip").addEventListener("click", () => toggleActiveTemplateDetails());
    // Switch-preset button → toggle the inline chooser panel.
    el("switch-preset-btn").addEventListener("click", () => togglePresetChooser());
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

    for (const radio of document.querySelectorAll('input[name="view-toggle"]')) {
        radio.addEventListener("change", () => {
            state.view = radio.value;
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
            startLastCheckedTicker();
        }
    });
}

// Settings navigation removed. Clockify's documented `navigate` postMessage
// only supports a fixed set of locations (per canonical
// Cldocs/01-canonical-docs/build/window-events.md, currently just "tracker"),
// not arbitrary paths. Our previous window.open workaround was making things
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

    setInterval(() => {
        messenger.refreshAddonToken();
    }, TOKEN_REFRESH_INTERVAL_MS);
}

function init() {
    document.title = ADDON_TITLE;
    wireEvents();
    wireVisibilityChange();
    startLastCheckedTicker();
    loadInitialData();
}

initAuthAndMessenger();
if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
} else {
    init();
}
