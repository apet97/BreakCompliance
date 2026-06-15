// Break Compliance — Checklist view (per-user cards, per-day rule lists with
// inline review cycling + evidence drill-down).
import { formatBreakWithSynthetic, formatMinutes, severityClass } from "../date-range.js";
import { create } from "../dom.js";
import {
    displayUserName,
    evidenceNotes,
    pickWorstSeverityFinding,
    reviewBadgeClass,
    reviewBadgeText,
    statusIconMeta,
} from "../findings-rendering.js";
import { isAdmin } from "../roles.js";
import { state } from "../state.js";
import { visibleFindings } from "../triage-metrics.js";

// OPEN -> ACKNOWLEDGED -> OVERRIDDEN -> OPEN, the checklist's single-button cycle.
function nextReviewStatus(current) {
    if (current === "ACKNOWLEDGED") return "OVERRIDDEN";
    if (current === "OVERRIDDEN") return "OPEN";
    return "ACKNOWLEDGED";
}

export function renderChecklist(container, { submitReview }) {
    const findingsView = visibleFindings(state.findings, state.userFilter);
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
                items.appendChild(renderRuleItem(f, admin, submitReview));
            }
            section.appendChild(items);
            days.appendChild(section);
        }
        card.appendChild(days);
        list.appendChild(card);
    }
    container.appendChild(list);
}

function renderRuleItem(f, admin, submitReview) {
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
        li.appendChild(create("span", {
            className: reviewBadgeClass(reviewStatus),
            text: badgeText,
            title: f.review?.note ? `Note: ${f.review.note}` : "No note recorded",
        }));
    }
    const reviewBtn = create("button", {
        className: "btn-link rule-review-btn",
        text: reviewStatus === "OPEN" ? "Mark…"
            : reviewStatus === "ACKNOWLEDGED" ? "→ Override"
            : "→ Re-open",
        title: admin ? "Cycle this finding's review state (admin only)" : "Workspace admin required to review findings",
    });
    reviewBtn.type = "button";
    reviewBtn.disabled = !admin;
    if (admin) {
        reviewBtn.addEventListener("click", () => submitReview(f.id, nextReviewStatus(reviewStatus)));
    }
    li.appendChild(reviewBtn);

    appendDrillDown(li, f);
    return li;
}

// P2.2 — surface entryIds + running-timer / overnight notes already in the
// evidence payload. No new endpoint needed.
function appendDrillDown(li, f) {
    const ev = evidenceNotes(f);
    if (!ev.hasAny) return;

    const label = () => `${ev.entryIds.length} ${ev.entryIds.length === 1 ? "entry" : "entries"}`
        + (ev.runningSkipped > 0 ? ` + ${ev.runningSkipped} running` : "");
    const drillBtn = create("button", {
        className: "btn-link rule-drill-btn",
        text: `▾ ${label()}`,
        title: "Show contributing time-entry ids",
    });
    drillBtn.type = "button";
    drillBtn.setAttribute("aria-expanded", "false");
    drillBtn.setAttribute("aria-label",
        `Show ${ev.entryIds.length} contributing time-entry id${ev.entryIds.length === 1 ? "" : "s"} for this finding`);

    const drillBody = create("div", { className: "rule-drill-body" });
    drillBody.hidden = true;
    if (ev.entryIds.length > 0) {
        const idList = create("ul", { className: "rule-drill-ids" });
        for (const id of ev.entryIds) idList.appendChild(create("li", { className: "rule-drill-id", text: id }));
        drillBody.appendChild(idList);
    }
    for (const note of ev.notes) drillBody.appendChild(create("p", { className: "rule-drill-note", text: note }));

    drillBtn.addEventListener("click", () => {
        const open = drillBody.hidden;
        drillBody.hidden = !open;
        drillBtn.setAttribute("aria-expanded", open ? "true" : "false");
        drillBtn.firstChild.nodeValue = (open ? "▴ " : "▾ ") + label();
    });
    li.appendChild(drillBtn);
    li.appendChild(drillBody);
}
