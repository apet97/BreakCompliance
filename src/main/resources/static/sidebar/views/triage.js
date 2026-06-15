// Break Compliance — Triage view (default).
//
// Triage-first surface: summary KPIs -> prioritized "Needs attention" feed ->
// "People with findings" roster. Built from the same state.findings the pivot
// and checklist render — no fabricated compliance %, since the backend only
// persists problem days (no compliant-day denominator). Renders into the shared
// #results-container; the orchestrator handles the zero-findings empty states
// before dispatching here.
//
// ctx: { submitReview(findingId, nextStatus), rerender() }.
import { createDateFormatters, formatMinutes, severityClass } from "../date-range.js";
import { create } from "../dom.js";
import { displayUserName, evidenceNotes, findingRuleLabel, statusIconMeta } from "../findings-rendering.js";
import { t } from "../i18n.js";
import { isAdmin } from "../roles.js";
import { state } from "../state.js";
import { buildRoster, initialsFor, prioritizedFeed, triageMetrics } from "../triage-metrics.js";

export function renderTriage(container, { submitReview, rerender }) {
    const metrics = triageMetrics(state.findings);

    // 1. KPI hero — computed over ALL findings so the headline stays stable
    //    while the feed below filters by selected person.
    const hero = create("div", { className: "triage-hero" });
    hero.appendChild(kpiCard(t("triage.open"), String(metrics.open), {
        sub: metrics.open > 0 ? openSplitSub(metrics.openFail, metrics.openWarn) : kpiSub(t("triage.openNone")),
    }));
    hero.appendChild(kpiCard(t("triage.peopleAffected"), String(metrics.peopleAffected), {
        sub: kpiSub(t("triage.peopleAffectedSub")),
    }));
    hero.appendChild(kpiCard(t("triage.reviewed"), String(metrics.reviewed), {
        ofTotal: metrics.total,
        sub: kpiSub(t("triage.reviewedSub")),
    }));
    container.appendChild(hero);

    const feed = prioritizedFeed(state.findings, state.userFilter);
    const fmt = createDateFormatters(state.session?.userTimeZone);

    // 2. Needs attention.
    const attn = create("div", { className: "triage-section" });
    const attnHead = create("div", { className: "triage-section-head" });
    attnHead.appendChild(create("h2", { text: t("triage.needsAttention") }));
    attnHead.appendChild(create("span", { className: "ct", text: String(feed.open.length) }));
    if (state.userFilter) {
        const clear = create("button", { className: "clear-filter", text: t("triage.clearFilter") });
        clear.type = "button";
        clear.addEventListener("click", () => { state.userFilter = null; rerender(); });
        attnHead.appendChild(clear);
    }
    attn.appendChild(attnHead);
    if (feed.open.length === 0) {
        attn.appendChild(allClearBlock());
    } else {
        const listEl = create("div", { className: "feed" });
        for (const f of feed.open) listEl.appendChild(createFindingCard(f, fmt, submitReview));
        attn.appendChild(listEl);
    }
    container.appendChild(attn);

    // 3. Reviewed (only when there are reviewed findings in scope).
    if (feed.reviewed.length > 0) {
        const rev = create("div", { className: "triage-section" });
        const revHead = create("div", { className: "triage-section-head" });
        revHead.appendChild(create("h2", { text: t("triage.reviewedSection") }));
        revHead.appendChild(create("span", { className: "ct", text: String(feed.reviewed.length) }));
        rev.appendChild(revHead);
        const revList = create("div", { className: "feed" });
        for (const f of feed.reviewed) revList.appendChild(createFindingCard(f, fmt, submitReview));
        rev.appendChild(revList);
        container.appendChild(rev);
    }

    // 4. People with findings (risk-sorted). Clicking filters the feed and
    //    keeps the #user-filter-select dropdown in sync via shared state.
    const roster = buildRoster(state.findings, state.lastRunRange);
    if (roster.length > 0) {
        const section = create("div", { className: "triage-section" });
        const head = create("div", { className: "triage-section-head" });
        head.appendChild(create("h2", { text: t("triage.peopleWithFindings") }));
        head.appendChild(create("span", { className: "ct", text: t("triage.byRisk") }));
        section.appendChild(head);
        const card = create("div", { className: "roster" });
        for (const person of roster) card.appendChild(createRosterRow(person, rerender));
        section.appendChild(card);
        container.appendChild(section);
    }
}

// KpiCard. Pass { ofTotal } to append a muted "/ N" denominator to the value
// (e.g. Reviewed 1 / 4); pass { sub } for the muted sub-line node.
function kpiCard(label, value, { sub, ofTotal } = {}) {
    const card = create("div", { className: "bc-kpi" });
    card.appendChild(create("div", { className: "bc-kpi-label", text: label }));
    const valueEl = create("div", { className: "bc-kpi-value" });
    valueEl.appendChild(document.createTextNode(value));
    if (ofTotal !== undefined) valueEl.appendChild(create("span", { className: "sub-of", text: ` / ${ofTotal}` }));
    card.appendChild(valueEl);
    if (sub) card.appendChild(sub);
    return card;
}

function kpiSub(text) {
    return create("div", { className: "bc-kpi-sub", text });
}

function openSplitSub(fail, warn) {
    const sub = create("div", { className: "bc-kpi-sub" });
    sub.appendChild(create("span", { className: "fail", text: `${fail} fail` }));
    sub.appendChild(document.createTextNode(" · "));
    sub.appendChild(create("span", { className: "warn", text: `${warn} warn` }));
    return sub;
}

function allClearBlock() {
    const block = create("div", { className: "all-clear" });
    const check = create("div", { className: "check", text: "✓" });
    check.setAttribute("aria-hidden", "true");
    block.appendChild(check);
    block.appendChild(create("h3", { text: t("triage.nothingOpen") }));
    const detail = state.userFilter
        ? t("triage.nothingOpenForPerson", { name: rosterNameFor(state.userFilter) })
        : t("triage.nothingOpenDetail");
    block.appendChild(create("p", { text: detail }));
    return block;
}

function rosterNameFor(userId) {
    const f = state.findings.find(x => x.userId === userId);
    return f ? displayUserName([f], userId) : userId;
}

// One design-system FindingCard, hand-rendered (no React). Open findings show
// Acknowledge / Override actions; reviewed findings dim and show a tag + Undo.
function createFindingCard(f, fmt, submitReview) {
    const admin = isAdmin();
    const status = severityClass(f.severity); // pass | warn | fail
    const reviewStatus = f.review?.status ?? "OPEN";
    const reviewed = reviewStatus !== "OPEN";
    const name = displayUserName([f], f.userId);

    const card = create("div", { className: "bc-finding" + (reviewed ? " reviewed" : "") });
    card.appendChild(create("div", { className: `bc-finding-rail ${status}` }));

    const avatar = create("div", { className: "bc-avatar" + (reviewed ? "" : " risk"), text: initialsFor(name) });
    avatar.setAttribute("aria-hidden", "true");
    card.appendChild(avatar);

    const main = create("div", { className: "bc-finding-main" });
    const top = create("div", { className: "bc-finding-top" });
    top.appendChild(create("span", { className: "bc-finding-person", title: f.userId, text: name }));
    const d = new Date(`${f.date}T12:00:00Z`);
    const worked = t("triage.worked", { duration: formatMinutes(f.evidence?.workMinutes) });
    const whenLabel = isNaN(d.getTime())
        ? worked
        : `${fmt.weekday.format(d)} ${fmt.monthDay.format(d)} · ${worked}`;
    top.appendChild(create("span", { className: "bc-finding-when", text: whenLabel }));
    main.appendChild(top);

    const rule = create("div", { className: "bc-finding-rule" });
    rule.appendChild(create("span", { className: `sev-dot ${status}`, title: statusIconMeta(f.severity).srLabel }));
    rule.appendChild(document.createTextNode(findingRuleLabel(f.code)));
    main.appendChild(rule);

    main.appendChild(create("div", { className: "bc-finding-detail", text: f.message }));

    appendEvidenceDrill(main, f);
    card.appendChild(main);

    card.appendChild(reviewed
        ? reviewedActions(f, reviewStatus, admin, submitReview)
        : openActions(f, admin, submitReview));
    return card;
}

function openActions(f, admin, submitReview) {
    const actions = create("div", { className: "bc-finding-actions" });
    const ack = create("button", { className: "bc-act-btn primary", text: t("triage.acknowledge") });
    ack.type = "button";
    const ovr = create("button", { className: "bc-act-btn ghost", text: t("triage.override") });
    ovr.type = "button";
    if (admin) {
        ack.addEventListener("click", () => submitReview(f.id, "ACKNOWLEDGED"));
        ovr.addEventListener("click", () => submitReview(f.id, "OVERRIDDEN"));
    } else {
        for (const b of [ack, ovr]) disableForNonAdmin(b);
    }
    actions.appendChild(ack);
    actions.appendChild(ovr);
    return actions;
}

function reviewedActions(f, reviewStatus, admin, submitReview) {
    const actions = create("div", { className: "bc-finding-actions" });
    const tagCls = reviewStatus === "ACKNOWLEDGED" ? "ack" : "override";
    actions.appendChild(create("span", {
        className: `bc-review-tag ${tagCls}`,
        text: reviewStatus === "ACKNOWLEDGED" ? "Ack" : "Override",
        title: f.review?.note ? `Note: ${f.review.note}` : "No note recorded",
    }));
    const undo = create("button", { className: "bc-undo-btn", text: t("triage.undo") });
    undo.type = "button";
    if (admin) undo.addEventListener("click", () => submitReview(f.id, "OPEN"));
    else disableForNonAdmin(undo);
    actions.appendChild(undo);
    return actions;
}

function disableForNonAdmin(btn) {
    btn.disabled = true;
    btn.setAttribute("aria-disabled", "true");
    btn.title = "Workspace admin required to review findings";
}

// Collapsible evidence chip — same payload the checklist drill-down surfaces.
function appendEvidenceDrill(main, f) {
    const ev = evidenceNotes(f);
    if (!ev.hasAny) return;

    const toggle = create("button", {
        className: "bc-finding-evidence",
        text: t("triage.evidenceCount", { n: ev.entryIds.length })
            + (ev.runningSkipped > 0 ? ` + ${ev.runningSkipped} running` : ""),
    });
    toggle.type = "button";
    toggle.setAttribute("aria-expanded", "false");
    toggle.setAttribute("aria-label",
        `Show ${ev.entryIds.length} contributing time-entry id${ev.entryIds.length === 1 ? "" : "s"} for this finding`);

    const body = create("div", { className: "bc-evidence-ids" });
    body.hidden = true;
    if (ev.entryIds.length > 0) {
        body.appendChild(create("span", { className: "lbl", text: "Time entries" }));
        for (const id of ev.entryIds) body.appendChild(create("code", { text: id }));
    }
    for (const note of ev.notes) body.appendChild(create("p", { className: "rule-drill-note", text: note }));

    toggle.addEventListener("click", () => {
        const open = body.hidden;
        body.hidden = !open;
        toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
    main.appendChild(toggle);
    main.appendChild(body);
}

// One roster row. The right-side number is the open-finding count (colored by
// worst open severity) — not a compliance %, which the backend can't supply.
function createRosterRow(person, rerender) {
    const selected = state.userFilter === person.userId;
    const row = create("button", { className: "bc-roster-row" + (selected ? " selected" : "") });
    row.type = "button";
    row.setAttribute("aria-pressed", selected ? "true" : "false");

    const avatar = create("div", { className: "bc-avatar" + (person.open > 0 ? " risk" : ""), text: person.initials });
    avatar.setAttribute("aria-hidden", "true");
    row.appendChild(avatar);

    const mid = create("div", { className: "bc-roster-mid" });
    mid.appendChild(create("div", { className: "bc-roster-name", title: person.userId, text: person.name }));
    const strip = create("div", { className: "bc-roster-strip" });
    for (const day of person.strip) {
        strip.appendChild(create("span", {
            className: `bc-strip-dot status-${day.status}`,
            title: `${day.date} — ${day.status === "none" ? "no findings" : day.status}`,
        }));
    }
    mid.appendChild(strip);
    row.appendChild(mid);

    const rate = create("div", { className: "bc-roster-rate" });
    const countCls = person.worstOpenStatus === "fail" ? " low"
        : person.worstOpenStatus === "warn" ? " mid" : "";
    rate.appendChild(create("div", { className: "bc-roster-count" + countCls, text: String(person.open) }));
    rate.appendChild(create("div", { className: "bc-roster-open", text: `${person.total} total` }));
    row.appendChild(rate);

    row.addEventListener("click", () => {
        state.userFilter = selected ? null : person.userId;
        rerender();
    });
    return row;
}
