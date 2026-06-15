// Break Compliance — triage view derivations.
//
// Pure functions (no DOM) that turn the findings list into the numbers and
// orderings the triage-first view renders: summary KPIs, a prioritized
// attention feed, and a "people with findings" roster. Mirrors the
// honest subset of the design system's Insights concept (concepts/insights/
// data.js compute()/feed()) — note the backend only persists findings for
// problem days, so there is no compliant-day denominator and therefore no
// fabricated compliance rate here.
//
// Kept DOM-free and side-effect-free so it can be unit-tested under node:test
// the same way sidebar/diagnostics.js is.

import { enumerateDates, severityClass } from "./date-range.js";
import { displayUserName } from "./findings-rendering.js";

// How many trailing day-dots the roster strip shows. The strip is a compact
// recency glance, not a full ledger; long ranges (e.g. the 90-day backlog)
// would otherwise wrap into an unreadable block in the narrow column.
const MAX_STRIP_DAYS = 14;

// pass < warn < fail in *visual* terms, but for "worst first" ordering fail
// ranks lowest (0) so it sorts to the top.
const STATUS_RANK = { fail: 0, warn: 1, pass: 2, none: 3 };

// A finding is open when it has no review row or its review is still OPEN.
export function isOpenFinding(finding) {
    const status = finding?.review?.status;
    return !status || status === "OPEN";
}

// Findings narrowed to the selected user (null/empty = everyone). Shared by the
// pivot and checklist views; the triage feed filters via prioritizedFeed.
export function visibleFindings(findings, userFilter) {
    const list = Array.isArray(findings) ? findings : [];
    return userFilter ? list.filter(f => f.userId === userFilter) : list;
}

function statusRank(status) {
    return STATUS_RANK[status] ?? STATUS_RANK.none;
}

function worstStatus(findings) {
    let best = null;
    for (const f of findings) {
        const s = severityClass(f.severity);
        if (best === null || statusRank(s) < statusRank(best)) best = s;
    }
    return best;
}

export function initialsFor(name) {
    const trimmed = String(name ?? "").trim();
    if (!trimmed) return "?";
    const parts = trimmed.split(/\s+/).filter(Boolean);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

// Summary KPIs over the full findings set (never filtered by the selected
// person — the headline stays stable while the feed filters).
export function triageMetrics(findings) {
    const list = Array.isArray(findings) ? findings : [];
    let open = 0, openFail = 0, openWarn = 0, reviewed = 0;
    const affected = new Set();
    for (const f of list) {
        if (isOpenFinding(f)) {
            open++;
            if (severityClass(f.severity) === "fail") openFail++;
            else openWarn++;
            if (f.userId) affected.add(f.userId);
        } else {
            reviewed++;
        }
    }
    return { open, openFail, openWarn, reviewed, total: list.length, peopleAffected: affected.size };
}

// Split + sort findings for the attention feed: open (fail -> warn -> pass,
// then by date) and reviewed (same ordering). Optionally narrowed to one user.
export function prioritizedFeed(findings, userFilter = null) {
    const list = (Array.isArray(findings) ? findings : [])
        .filter(f => !userFilter || f.userId === userFilter);
    const sorter = (a, b) => {
        const ra = statusRank(severityClass(a.severity));
        const rb = statusRank(severityClass(b.severity));
        if (ra !== rb) return ra - rb;
        const da = String(a.date ?? "");
        const db = String(b.date ?? "");
        if (da !== db) return da < db ? -1 : 1;
        return String(a.id ?? "").localeCompare(String(b.id ?? ""));
    };
    const open = list.filter(isOpenFinding).sort(sorter);
    const reviewed = list.filter(f => !isOpenFinding(f)).sort(sorter);
    return { open, reviewed };
}

// One row per user that has any finding, sorted by risk: most open findings
// first, then by worst open severity, then by name. Each row carries a
// compact recency strip (trailing days of the range; days without a finding
// render neutral, matching the pivot's "no findings" convention).
export function buildRoster(findings, dateRange = null) {
    const list = Array.isArray(findings) ? findings : [];
    const byUser = new Map();
    for (const f of list) {
        if (!f.userId) continue;
        if (!byUser.has(f.userId)) byUser.set(f.userId, []);
        byUser.get(f.userId).push(f);
    }

    let stripDates = (dateRange?.start && dateRange?.end)
        ? enumerateDates(dateRange.start, dateRange.end)
        : [];
    if (stripDates.length > MAX_STRIP_DAYS) stripDates = stripDates.slice(-MAX_STRIP_DAYS);

    const rows = [];
    for (const [userId, userFindings] of byUser) {
        const openFindings = userFindings.filter(isOpenFinding);
        const datesFallback = stripDates.length === 0
            ? [...new Set(userFindings.map(f => f.date))].sort().slice(-MAX_STRIP_DAYS)
            : stripDates;
        const strip = datesFallback.map(date => {
            const onDay = userFindings.filter(f => f.date === date);
            return { date, status: onDay.length ? worstStatus(onDay) : "none" };
        });
        const name = displayUserName(userFindings, userId);
        rows.push({
            userId,
            name,
            initials: initialsFor(name),
            open: openFindings.length,
            total: userFindings.length,
            worstOpenStatus: openFindings.length ? worstStatus(openFindings) : null,
            strip,
        });
    }

    rows.sort((a, b) => {
        if (a.open !== b.open) return b.open - a.open;
        const ra = statusRank(a.worstOpenStatus ?? worstStatus(byUser.get(a.userId)) ?? "none");
        const rb = statusRank(b.worstOpenStatus ?? worstStatus(byUser.get(b.userId)) ?? "none");
        if (ra !== rb) return ra - rb;
        return a.name.localeCompare(b.name);
    });
    return rows;
}
