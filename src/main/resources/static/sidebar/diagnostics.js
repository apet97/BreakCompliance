function nonNegativeIntegerText(value) {
    if (value == null || value === "") return null;
    const n = Number(value);
    if (!Number.isInteger(n) || n < 0) return null;
    return String(n);
}

export function diagnosticMetricRows(lastRun) {
    if (!lastRun) return [];
    const rows = [];
    const entriesProcessed = nonNegativeIntegerText(lastRun.entriesProcessed);
    if (entriesProcessed != null) {
        rows.push({ label: "Entries ingested", value: entriesProcessed });
    }
    const findingsCreated = nonNegativeIntegerText(lastRun.findingsCreated);
    if (findingsCreated != null) {
        rows.push({ label: "Findings created", value: findingsCreated });
    }
    return rows;
}

export function applyFindingsCountToLastRun(sidebarState) {
    if (!sidebarState?.lastRun || !Array.isArray(sidebarState.findings)) return;
    sidebarState.lastRun.findingsCreated = sidebarState.findings.length;
}
