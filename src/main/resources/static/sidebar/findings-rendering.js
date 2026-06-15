import { severityClass } from "./date-range.js";
import { t } from "./i18n.js";

// Short, human display title for a finding code (presentation only — the
// persisted code/message/severity/CSV are unchanged). Falls back to the raw
// code when no translation key is registered.
export function findingRuleLabel(code) {
    if (!code) return "";
    const key = `finding.rule.${code}`;
    const label = t(key);
    return label === key ? code : label;
}

export function displayUserName(findings, userId) {
    for (const f of findings) {
        if (f.userName && typeof f.userName === "string" && f.userName.trim().length > 0) {
            return f.userName;
        }
    }
    return userId;
}

export function statusIconMeta(severity) {
    const cls = severityClass(severity);
    if (cls === "pass") return { icon: "✓", srLabel: "Pass" };
    if (cls === "warn") return { icon: "!", srLabel: "Warning" };
    return { icon: "✗", srLabel: "Violation" };
}

export function reviewBadgeText(status) {
    if (!status || status === "OPEN") return null;
    if (status === "ACKNOWLEDGED") return "Acknowledged";
    if (status === "OVERRIDDEN") return "Overridden";
    return null;
}

export function reviewBadgeClass(status) {
    if (status === "ACKNOWLEDGED") return "review-badge review-badge--ack";
    if (status === "OVERRIDDEN") return "review-badge review-badge--override";
    return "review-badge";
}

const SEVERITY_RANK = { VIOLATION: 3, WARNING: 2, INFO: 1 };

// The most severe finding in a same-day group (drives the day/cell status).
export function pickWorstSeverityFinding(findings) {
    return findings.reduce((worst, current) =>
        (SEVERITY_RANK[current.severity] ?? 0) > (SEVERITY_RANK[worst.severity] ?? 0)
            ? current : worst, findings[0]);
}

// Canonical evidence drill-down payload shared by the checklist and the triage
// FindingCard, so the entry-id extraction and the running-timer / overnight
// note copy live in exactly one place.
export function evidenceNotes(f) {
    const entryIds = Array.isArray(f?.evidence?.entryIds) ? f.evidence.entryIds : [];
    const runningSkipped = Number(f?.evidence?.runningEntriesSkipped) || 0;
    const overnightShifts = Number(f?.evidence?.overnightShifts) || 0;
    const notes = [];
    if (runningSkipped > 0) {
        notes.push(`${runningSkipped} running timer${runningSkipped === 1 ? "" : "s"} skipped — refresh once the user stops the entry to include it in the evaluation.`);
    }
    if (overnightShifts > 0) {
        notes.push(`${overnightShifts} entr${overnightShifts === 1 ? "y" : "ies"} crossed midnight — the full shift is attributed to the start-day; review before acting.`);
    }
    return {
        entryIds,
        runningSkipped,
        overnightShifts,
        notes,
        hasAny: entryIds.length > 0 || runningSkipped > 0 || overnightShifts > 0,
    };
}
