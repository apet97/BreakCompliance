import assert from "node:assert/strict";
import test from "node:test";

import {
    buildRoster,
    initialsFor,
    prioritizedFeed,
    triageMetrics,
    visibleFindings,
} from "../../main/resources/static/sidebar/triage-metrics.js";

// id, userId, userName, date, severity, review
function finding(id, userId, userName, date, severity, review = null) {
    return { id, userId, userName, date, severity, code: "MISSING_REQUIRED_BREAK", message: "m", evidence: {}, review };
}

const FIXTURE = [
    finding("f1", "u1", "Ana M", "2026-06-09", "VIOLATION"),                          // open fail
    finding("f2", "u1", "Ana M", "2026-06-10", "WARNING", { status: "OPEN" }),         // open warn
    finding("f3", "u2", "Jon D", "2026-06-09", "VIOLATION", { status: "ACKNOWLEDGED" }), // reviewed
    finding("f4", "u3", "Sam O", "2026-06-11", "WARNING"),                            // open warn
];

test("visibleFindings narrows to the selected user, or returns all when unfiltered", () => {
    assert.equal(visibleFindings(FIXTURE, null).length, 4);
    assert.deepEqual(visibleFindings(FIXTURE, "u1").map(f => f.id), ["f1", "f2"]);
    assert.deepEqual(visibleFindings(undefined, "u1"), []);
});

test("initialsFor builds at most two uppercase letters", () => {
    assert.equal(initialsFor("Ana M"), "AM");
    assert.equal(initialsFor("Jon"), "JO");
    assert.equal(initialsFor("  "), "?");
});

test("triageMetrics counts open/fail/warn/reviewed and distinct affected people", () => {
    assert.deepEqual(triageMetrics(FIXTURE), {
        open: 3,
        openFail: 1,
        openWarn: 2,
        reviewed: 1,
        total: 4,
        peopleAffected: 2, // u1 + u3 have open findings; u2's only finding is reviewed
    });
});

test("triageMetrics treats a null review as open", () => {
    const m = triageMetrics([finding("x", "u9", "N", "2026-06-09", "VIOLATION", null)]);
    assert.equal(m.open, 1);
    assert.equal(m.reviewed, 0);
});

test("prioritizedFeed orders fail before warn, then by date, and splits reviewed out", () => {
    const { open, reviewed } = prioritizedFeed(FIXTURE);
    assert.deepEqual(open.map(f => f.id), ["f1", "f2", "f4"]);
    assert.deepEqual(reviewed.map(f => f.id), ["f3"]);
});

test("prioritizedFeed narrows to a single user when filtered", () => {
    const { open, reviewed } = prioritizedFeed(FIXTURE, "u1");
    assert.deepEqual(open.map(f => f.id), ["f1", "f2"]);
    assert.deepEqual(reviewed.map(f => f.id), []);
});

test("buildRoster sorts by open count desc, strips span the range, fully-reviewed user reports zero open", () => {
    const roster = buildRoster(FIXTURE, { start: "2026-06-08", end: "2026-06-12" });
    assert.deepEqual(roster.map(r => r.userId), ["u1", "u3", "u2"]);

    const ana = roster[0];
    assert.equal(ana.open, 2);
    assert.equal(ana.total, 2);
    assert.equal(ana.worstOpenStatus, "fail");
    assert.equal(ana.strip.length, 5); // 2026-06-08 .. 2026-06-12 inclusive
    assert.equal(ana.strip.find(d => d.date === "2026-06-09").status, "fail");
    assert.equal(ana.strip.find(d => d.date === "2026-06-10").status, "warn");
    assert.equal(ana.strip.find(d => d.date === "2026-06-08").status, "none");

    const jon = roster.find(r => r.userId === "u2");
    assert.equal(jon.open, 0);
    assert.equal(jon.worstOpenStatus, null);
});

test("buildRoster breaks open-count ties by worst open severity", () => {
    const tie = [
        finding("a", "warnUser", "Warn User", "2026-06-09", "WARNING"),
        finding("b", "failUser", "Fail User", "2026-06-09", "VIOLATION"),
    ];
    const roster = buildRoster(tie, { start: "2026-06-09", end: "2026-06-09" });
    assert.deepEqual(roster.map(r => r.userId), ["failUser", "warnUser"]);
});

test("buildRoster falls back to finding dates when no range is supplied", () => {
    const roster = buildRoster([finding("a", "u1", "N", "2026-06-09", "VIOLATION")], null);
    assert.equal(roster[0].strip.length, 1);
    assert.equal(roster[0].strip[0].status, "fail");
});
