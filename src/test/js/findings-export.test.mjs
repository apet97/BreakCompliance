import assert from "node:assert/strict";
import test from "node:test";

import {
    buildFindingsExportUrl,
} from "../../main/resources/static/sidebar/findings-export.js";

test("buildFindingsExportUrl preserves open-only and selected-user filters", () => {
    assert.equal(buildFindingsExportUrl({
        start: "2026-06-01",
        end: "2026-06-14",
        openOnly: true,
        userId: "user-1",
    }), "/api/findings/export?dateRangeStart=2026-06-01&dateRangeEnd=2026-06-14&format=csv&openOnly=true&userIds=user-1");
});

test("buildFindingsExportUrl keeps unfiltered exports backward compatible", () => {
    assert.equal(buildFindingsExportUrl({
        start: "2026-06-01",
        end: "2026-06-14",
    }), "/api/findings/export?dateRangeStart=2026-06-01&dateRangeEnd=2026-06-14&format=csv");
});
