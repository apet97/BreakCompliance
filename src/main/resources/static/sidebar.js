// Break Compliance sidebar — Clockify add-on iframe client.
//
// Loads inside the Clockify-served iframe. On the initial top-level
// navigation Clockify cannot set headers, so the auth token arrives in
// the URL as `?auth_token=...`. We immediately read it, store it in
// module-scoped state, and strip it from the URL via History.replaceState
// (no fragment, no reload) so the token never leaks into Referer / access
// logs. Every subsequent /api/* call uses the `x-addon-token` header.
//
// The parent (Clockify) frame is reached via postMessage with the target
// origin set to ancestorOrigins[0] (or document.referrer fallback). We
// never postMessage to "*".
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

    function dispatch(title, body) {
        if (!parentOrigin || !target?.postMessage) return false;
        target.postMessage({ title, body: body ?? {} }, parentOrigin);
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
    const target = document.body ?? document.documentElement;
    if (!target) return;
    const resolved = theme === "DARK" ? "DARK" : "DEFAULT";
    if (resolved === "DARK") target.classList?.add("dark");
    else target.classList?.remove("dark");
    target.setAttribute?.("data-clockify-theme", resolved);
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
    if (minutes <= 0) return "0m";
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    if (h === 0) return `${m}m`;
    if (m === 0) return `${h}h`;
    return `${h}h ${m}m`;
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
        banner.style.display = "none";
        banner.textContent = "";
        return;
    }
    banner.style.display = "block";
    banner.className = kind === "err"
        ? "error-banner"
        : kind === "warn"
            ? "warn-banner"
            : kind === "ok"
                ? "ok-banner"
                : "panel panel-body";
    banner.textContent = message;
}

function setLoading(on) { el("loading").style.display = on ? "flex" : "none"; }

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
        node.style.display = "none";
        return;
    }
    node.style.display = "grid";
    node.appendChild(create("div", undefined, [
        create("div", { className: "label", text: "Entries ingested" }),
        create("div", { className: "value", text: String(state.lastRun.entriesProcessed) }),
    ]));
    node.appendChild(create("div", undefined, [
        create("div", { className: "label", text: "Findings created" }),
        create("div", { className: "value", text: String(state.lastRun.findingsCreated) }),
    ]));
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
        container.appendChild(create("p", {
            className: "no-data",
            text: "No findings in this range. Click Check Compliance to run.",
        }));
        return;
    }
    if (state.view === "pivot") renderPivot(container);
    else renderChecklist(container);
}

function renderPivot(container) {
    const byUser = new Map();
    const allDates = new Set();
    for (const f of state.findings) {
        if (!byUser.has(f.userId)) byUser.set(f.userId, new Map());
        const byDate = byUser.get(f.userId);
        if (!byDate.has(f.date)) byDate.set(f.date, []);
        byDate.get(f.date).push(f);
        allDates.add(f.date);
    }
    const sortedDates = [...allDates].sort();

    const scroll = create("div", { className: "pivot-scroll-container" });
    const table = create("table", { className: "pivot-table" });
    const thead = create("thead");
    const headerRow = create("tr");
    headerRow.appendChild(create("th", { className: "user-col", text: "User" }));
    for (const date of sortedDates) {
        const d = new Date(`${date}T00:00:00Z`);
        const dayName = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"][d.getUTCDay()] ?? "";
        const dayDate = `${d.getUTCMonth() + 1}/${d.getUTCDate()}`;
        const th = create("th", { className: "day-col" });
        th.appendChild(create("div", { className: "day-name", text: dayName }));
        th.appendChild(create("div", { className: "day-date", text: dayDate }));
        headerRow.appendChild(th);
    }
    thead.appendChild(headerRow);
    table.appendChild(thead);

    const tbody = create("tbody");
    for (const [userId, byDate] of byUser) {
        const row = create("tr");
        row.appendChild(create("td", { className: "user-col", title: userId, text: userId }));
        for (const date of sortedDates) {
            const findings = byDate.get(date) ?? [];
            if (findings.length === 0) {
                const cell = create("td", { className: "day-cell status-none" });
                cell.appendChild(create("span", { className: "status-icon", text: "·" }));
                row.appendChild(cell);
                continue;
            }
            const worst = pickWorstSeverityFinding(findings);
            const cls = severityClass(worst.severity);
            const icon = cls === "pass" ? "✓" : cls === "warn" ? "!" : "✗";
            const summary = `${formatMinutes(worst.evidence.workMinutes)} · ${formatMinutes(worst.evidence.breakMinutes)}`;
            const cell = create("td", { className: `day-cell status-${cls}`, title: worst.message });
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
        card.appendChild(create("div", { className: "user-name", text: userId }));
        const days = create("div", { className: "day-list" });
        for (const date of [...byDate.keys()].sort()) {
            const findings = byDate.get(date);
            const worst = pickWorstSeverityFinding(findings);
            const cls = severityClass(worst.severity);
            const icon = cls === "pass" ? "✓" : cls === "warn" ? "!" : "✗";
            const summary = `Work: ${formatMinutes(worst.evidence.workMinutes)} | Break: ${formatMinutes(worst.evidence.breakMinutes)}`;
            const section = create("div", { className: `day-section status-${cls}` });
            const header = create("div", { className: "day-header" });
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

async function runCompliance() {
    showBanner("hidden");
    const range = computeDateRange();
    if ("error" in range) {
        showBanner("err", range.error);
        return;
    }
    setRunButtonState(true);
    setLoading(true);

    let entriesProcessed = 0;
    try {
        const ingest = await api("/api/ingest/detailed-report", {
            method: "POST",
            body: JSON.stringify({ dateRangeStart: range.start, dateRangeEnd: range.end }),
        });
        entriesProcessed = ingest.run.entriesProcessed;
    } catch (err) {
        setLoading(false);
        setRunButtonState(false);
        if (err instanceof HttpError) {
            const code = String(err.body?.error ?? err.message);
            if (err.status === 503 && code === "reports_unavailable") {
                // Dev-portal / non-production workspace: not a real failure, friendly notice.
                showBanner("warn", err.body?.message
                    ?? "Reports API is unavailable in this workspace. Install in a production Clockify workspace to run full compliance checks.");
            } else if (err.status === 503 && code === "installation_not_found") {
                showBanner("err",
                    "Add-on not installed for this workspace yet. Re-install from the marketplace and try again.");
            } else {
                showBanner("err",
                    `Ingestion failed: ${code}. The run is recorded so an admin can audit it from Settings.`);
            }
            return;
        }
        showBanner("err", "Unexpected ingestion failure.");
        return;
    }

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
    setRunButtonState(false);
    setLoading(false);
    renderDiagnostics();
    renderResults();
    if (state.findings.length === 0) {
        showBanner("ok", `Range ${range.start} → ${range.end}: no break-compliance issues.`);
    } else {
        showBanner("warn", `Range ${range.start} → ${range.end}: ${state.findings.length} finding(s).`);
    }
}

// ─────────────────── Form sync + initial loads ───────────────────

function updateFormFromState() {
    el("jurisdiction-select").value = state.jurisdiction;
    el("date-preset-select").value = state.preset;
    el("custom-range-inputs").style.display = state.preset === "custom_range" ? "flex" : "none";
}

async function loadInitialData() {
    try {
        state.session = await api("/api/session");
        el("session-status").textContent = `Connected · ${state.session.workspaceId}`;
    } catch (err) {
        el("session-status").textContent = "Not connected";
        showBanner("err", err instanceof HttpError ? `Session error: ${err.message}` : "Session error.");
        return;
    }

    try {
        const { templates } = await api("/api/templates");
        const select = el("jurisdiction-select");
        clearChildren(select);
        for (const t of templates) {
            const opt = document.createElement("option");
            opt.value = t.presetKey ?? t.id;
            opt.textContent = t.type === "BUILT_IN" ? `Template · ${t.name}` : `Custom · ${t.name}`;
            select.appendChild(opt);
        }
        if (templates.length > 0) {
            state.jurisdiction = templates[0].presetKey ?? templates[0].id;
        }
    } catch {
        // Silent: dropdown keeps server-rendered defaults from the HTML shell.
    }
    updateFormFromState();
}

// ─────────────────── Export ───────────────────

function downloadExport(event, format) {
    event.preventDefault();
    const range = computeDateRange();
    if ("error" in range) { showBanner("err", range.error); return; }

    const path = format === "json" ? "/api/findings/export.json" : "/api/findings/export.csv";
    const params = new URLSearchParams({ dateRangeStart: range.start, dateRangeEnd: range.end });

    api(`${path}?${params}`).then(payload => {
        const text = format === "json" ? JSON.stringify(payload, null, 2) : String(payload);
        const blob = new Blob([text], { type: format === "json" ? "application/json" : "text/csv" });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = `break-compliance-${range.start}-${range.end}.${format}`;
        anchor.click();
        URL.revokeObjectURL(url);
    }).catch(err => showBanner("err", err instanceof HttpError ? `Export failed: ${err.message}` : "Export failed."));
}

// ─────────────────── Event wiring ───────────────────

function wireEvents() {
    el("jurisdiction-select").addEventListener("change", e => {
        state.jurisdiction = e.target.value;
    });
    el("date-preset-select").addEventListener("change", e => {
        state.preset = e.target.value;
        updateFormFromState();
    });
    el("custom-start-date").addEventListener("change", e => { state.customStart = e.target.value; });
    el("custom-end-date").addEventListener("change", e => { state.customEnd = e.target.value; });
    el("run-btn").addEventListener("click", () => { runCompliance(); });

    for (const radio of document.querySelectorAll('input[name="view-toggle"]')) {
        radio.addEventListener("change", () => {
            state.view = radio.value;
            renderResults();
        });
    }
    el("export-json").addEventListener("click", e => downloadExport(e, "json"));
    el("export-csv").addEventListener("click", e => downloadExport(e, "csv"));
}

// ─────────────────── Boot ───────────────────

function initAuthAndMessenger() {
    const token = readAuthTokenFromQuery();
    if (token) addonToken = token;
    stripAuthTokenFromUrl();
    messenger = createMessenger();

    const theme = (new URLSearchParams(window.location.search).get("theme") ?? "").toUpperCase();
    if (theme === "DARK" || theme === "LIGHT") applyTheme(theme);

    setInterval(() => {
        messenger.refreshAddonToken();
        const refreshed = readAuthTokenFromQuery();
        if (refreshed) addonToken = refreshed;
    }, TOKEN_REFRESH_INTERVAL_MS);
}

function init() {
    document.title = ADDON_TITLE;
    wireEvents();
    loadInitialData();
}

initAuthAndMessenger();
if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
} else {
    init();
}
