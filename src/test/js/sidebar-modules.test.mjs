// Import-resolution smoke test for the sidebar module graph.
//
// `node --check` only parses each file; it does NOT resolve ESM imports, so a
// mismatched import/export name (e.g. renaming a view export but not its
// caller) passes the syntax gate yet breaks at runtime in the iframe. These
// imports execute each module's top level and assert its public entry points
// exist — catching exactly that class of regression. (sidebar.js itself is not
// imported here: it boots auth/messenger against window on load.)
import assert from "node:assert/strict";
import test from "node:test";

const BASE = "../../main/resources/static/sidebar";

test("view modules resolve and export their render entry points", async () => {
    const triage = await import(`${BASE}/views/triage.js`);
    const pivot = await import(`${BASE}/views/pivot.js`);
    const checklist = await import(`${BASE}/views/checklist.js`);
    assert.equal(typeof triage.renderTriage, "function");
    assert.equal(typeof pivot.renderPivot, "function");
    assert.equal(typeof checklist.renderChecklist, "function");
});

test("preset-ui exports the surface the orchestrator wires up", async () => {
    const presetUi = await import(`${BASE}/views/preset-ui.js`);
    for (const name of [
        "configurePresetUi",
        "loadPresetCatalog",
        "renderActiveTemplate",
        "renderCustomizedPill",
        "toggleActiveTemplateDetails",
        "togglePresetChooser",
    ]) {
        assert.equal(typeof presetUi[name], "function", `preset-ui must export ${name}`);
    }
});

test("shared helper modules export their reused functions", async () => {
    const roles = await import(`${BASE}/roles.js`);
    const findings = await import(`${BASE}/findings-rendering.js`);
    assert.equal(typeof roles.isAdmin, "function");
    assert.equal(typeof findings.evidenceNotes, "function");
    assert.equal(typeof findings.pickWorstSeverityFinding, "function");
    assert.equal(typeof findings.displayUserName, "function");
});
