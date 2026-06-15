// Break Compliance — Pivot view (user × day grid). Read-only, no interactions.
import { createDateFormatters, enumerateDates, formatBreakWithSynthetic, formatMinutes, severityClass } from "../date-range.js";
import { create } from "../dom.js";
import { displayUserName, pickWorstSeverityFinding, statusIconMeta } from "../findings-rendering.js";
import { state } from "../state.js";
import { visibleFindings } from "../triage-metrics.js";

export function renderPivot(container) {
    const findingsView = visibleFindings(state.findings, state.userFilter);
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
