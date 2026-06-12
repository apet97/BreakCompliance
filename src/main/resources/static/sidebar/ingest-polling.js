import { HttpError } from "./api-client.js";

export async function pollIngestionRun({ api, runId, range, isCanceled, setLoadingMessage }) {
    let delay = 800;
    const maxDelay = 4000;
    while (true) {
        if (isCanceled?.()) {
            const err = new Error("canceled");
            err.canceled = true;
            throw err;
        }
        const body = await api(`/api/ingest/runs/${encodeURIComponent(runId)}`);
        if (body.status === "COMPLETED" || body.status === "FAILED") return body;
        if (body.status === "RUNNING") {
            const processed = Number(body.entriesProcessed) || 0;
            setLoadingMessage(processed > 0
                ? `Imported ${processed.toLocaleString()} entries…`
                : `Fetching ${range.start} → ${range.end} from Clockify…`);
        }
        await new Promise(r => setTimeout(r, delay));
        delay = Math.min(maxDelay, Math.round(delay * 1.4));
    }
}

export function describeIngestFailure(errorCode) {
    if (!errorCode) return "Ingestion failed. The run was recorded — re-open the addon and try again.";
    if (errorCode === "ClockifyApi:401") {
        return "Reports API is unavailable in this workspace. Install in a production Clockify workspace to run full compliance checks.";
    }
    if (errorCode.startsWith("ClockifyApi:")) {
        return `Clockify rejected the report request (${errorCode}). Try again in a minute; if it persists, re-open the addon to refresh credentials.`;
    }
    return `Ingestion failed (${errorCode}). The run was recorded so an admin can audit it from Settings.`;
}

export function isHttpError(err) {
    return err instanceof HttpError;
}
