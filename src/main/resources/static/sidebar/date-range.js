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

export const DATE_PRESETS = {
    today: () => dayRange(0),
    this_week: () => weekRange(0),
    last_week: () => weekRange(-1),
    last_2_weeks: () => weekRange(-2, 0),
    last_month: () => monthRange(-1),
    all_open: () => {
        const end = new Date();
        const start = new Date();
        start.setDate(start.getDate() - 90);
        return { start: isoDate(start), end: isoDate(end), openOnly: true };
    },
};

export function formatMinutes(minutes) {
    if (minutes == null || minutes <= 0) return "0m";
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    if (h === 0) return `${m}m`;
    if (m === 0) return `${h}h`;
    return `${h}h ${m}m`;
}

export function formatBreakWithSynthetic(evidence) {
    const total = evidence?.breakMinutes ?? 0;
    const synth = evidence?.syntheticBreakMinutes ?? 0;
    if (!synth) return formatMinutes(total);
    return `${formatMinutes(total)} · ${formatMinutes(synth)} detected`;
}

export function formatRelativeTime(date) {
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

export function severityClass(severity) {
    if (severity === "INFO") return "pass";
    if (severity === "WARNING") return "warn";
    return "fail";
}

export function enumerateDates(startIso, endIso) {
    const out = [];
    if (!startIso || !endIso) return out;
    const start = new Date(`${startIso}T00:00:00Z`);
    const end = new Date(`${endIso}T00:00:00Z`);
    if (isNaN(start.getTime()) || isNaN(end.getTime())) return out;
    const MAX_DAYS = 45;
    let count = 0;
    for (let d = start; d <= end && count < MAX_DAYS; d.setUTCDate(d.getUTCDate() + 1)) {
        out.push(`${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`);
        count++;
    }
    return out;
}

export function createDateFormatters(timeZone) {
    const tz = timeZone || undefined;
    const safe = (opts) => {
        try {
            return new Intl.DateTimeFormat(undefined, { ...opts, timeZone: tz });
        } catch {
            return new Intl.DateTimeFormat(undefined, opts);
        }
    };
    return {
        weekday: safe({ weekday: "short" }),
        monthDay: safe({ month: "numeric", day: "numeric" }),
    };
}
