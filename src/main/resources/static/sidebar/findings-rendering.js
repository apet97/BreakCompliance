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
