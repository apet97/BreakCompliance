export class HttpError extends Error {
    constructor(status, body, message) {
        super(message ?? `HTTP ${status}`);
        this.status = status;
        this.body = body;
    }
}

export function buildApiUrl(path, query) {
    const url = new URL(path, window.location.origin);
    if (query) {
        for (const [k, v] of Object.entries(query)) {
            if (v != null && v !== "") url.searchParams.set(k, v);
        }
    }
    return url.pathname + (url.search ? `?${url.searchParams.toString()}` : "");
}

export function createApiClient(getToken) {
    return async function request(path, options = {}) {
        const token = getToken();
        if (!token) throw new HttpError(401, { error: "token_missing" });
        const { query, ...rest } = options;
        const headers = new Headers(rest.headers);
        headers.set("x-addon-token", token);
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
    };
}
