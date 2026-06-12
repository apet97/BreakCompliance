import { buildApiUrl, HttpError } from "./api-client.js";

export async function downloadFindingsCsv({ range, token, showBanner }) {
    if (!range) {
        showBanner("err", "Pick a date range before exporting.");
        return;
    }
    const url = buildApiUrl("/api/findings/export", {
        dateRangeStart: range.start,
        dateRangeEnd: range.end,
        format: "csv",
    });
    let blob;
    let filename = `break-compliance-${range.start}-${range.end}.csv`;
    try {
        const response = await fetch(url, { headers: { "x-addon-token": token } });
        if (!response.ok) {
            throw new HttpError(response.status, null, `HTTP ${response.status}`);
        }
        const cd = response.headers.get("Content-Disposition") || "";
        const match = cd.match(/filename="([^"\\]+)"/);
        if (match) filename = match[1];
        blob = await response.blob();
    } catch (err) {
        showBanner("err", err instanceof HttpError
            ? `Export failed (${err.status}). Try again, or refresh the addon if the error persists.`
            : "Export failed. Try again, or refresh the addon if the error persists.");
        return;
    }
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = filename;
    anchor.rel = "noopener";
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    requestAnimationFrame(() => URL.revokeObjectURL(objectUrl));
}
