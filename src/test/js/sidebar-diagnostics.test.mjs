import assert from "node:assert/strict";
import test from "node:test";

import {
    applyFindingsCountToLastRun,
    diagnosticMetricRows,
} from "../../main/resources/static/sidebar/diagnostics.js";

test("diagnosticMetricRows omits unknown findingsCreated instead of rendering null", () => {
    assert.deepEqual(diagnosticMetricRows({
        entriesProcessed: 6,
        findingsCreated: null,
    }), [
        { label: "Entries ingested", value: "6" },
    ]);
});

test("diagnosticMetricRows renders a loaded zero findings count", () => {
    assert.deepEqual(diagnosticMetricRows({
        entriesProcessed: 6,
        findingsCreated: 0,
    }), [
        { label: "Entries ingested", value: "6" },
        { label: "Findings created", value: "0" },
    ]);
});

test("applyFindingsCountToLastRun hydrates latest-run diagnostics from loaded findings", () => {
    const sidebarState = {
        lastRun: { entriesProcessed: 6, findingsCreated: null },
        findings: [{ id: "finding-1" }, { id: "finding-2" }],
    };

    applyFindingsCountToLastRun(sidebarState);

    assert.equal(sidebarState.lastRun.findingsCreated, 2);
});
