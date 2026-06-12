const QUERY_PARAM_AUTH_TOKEN = "auth_token";

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

export function readAuthTokenFromQuery() {
    const search = window.location?.search ?? "";
    return new URLSearchParams(search).get(QUERY_PARAM_AUTH_TOKEN);
}

export function stripAuthTokenFromUrl() {
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

export function createMessenger() {
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

export function applyTheme(theme) {
    const target = document.body ?? document.documentElement;
    if (!target) return;
    const resolved = theme === "DARK" ? "dark" : "light";
    target.classList.toggle("dark", resolved === "dark");
    target.setAttribute("data-clockify-theme", resolved);
}
