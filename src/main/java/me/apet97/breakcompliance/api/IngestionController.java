package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kicks off an async detailed-report ingest. The controller commits the
 * {@link IngestionRun} row in {@code RUNNING} state and returns 202 with
 * the run id so the sidebar can poll {@code GET /api/ingest/runs/{id}}
 * for status. The long Clockify HTTP call + DB upsert run on the
 * {@code ingestExecutor} pool (see {@code AsyncConfig}); their outcome is
 * captured on the run row, not propagated to this controller.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    @PostMapping(value = "/detailed-report", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingest(
            HttpServletRequest request, @RequestBody IngestRequest body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.requireAdmin(claims);
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(
                body == null ? null : body.dateRangeStart(),
                body == null ? null : body.dateRangeEnd());
        LocalDate from = range.from();
        LocalDate to = range.to();
        IngestionRun run;
        try {
            run = service.beginAsync(claims.workspaceId(), from, to, claims.reportsUrl());
        } catch (InstallationInactiveException e) {
            // Lifecycle STATUS_CHANGED → INACTIVE: stop all operations for
            // this workspace per the marketplace contract. Friendly 503 so
            // the sidebar surfaces it as a banner.
            return ResponseEntity.status(503).body(Map.of(
                    "error", "installation_inactive",
                    "message", "This workspace's add-on is currently inactive. Re-enable it from Workspace Settings → Add-ons → Break Compliance to run compliance checks."));
        } catch (IngestionRunInProgressException e) {
            // Admin double-click or overlapping webhook trigger. Point them
            // at the already-running run instead of queueing a duplicate.
            return ResponseEntity.status(409).body(Map.of(
                    "error", "ingest_in_progress",
                    "existingRunId", e.existingRunId(),
                    "pollUrl", "/api/ingest/runs/" + e.existingRunId(),
                    "message", "An ingest is already running for this workspace and date range; reuse the existing run."));
        }
        // 202 Accepted: the prepare step (sync, transactional) committed
        // the run row. The actual fetch + finalize is now executing on
        // the ingest pool; the sidebar polls /api/ingest/runs/{id} for
        // progress and terminal state. Errors that happen after this
        // point (Clockify 401 / network) land on the run as errorCode.
        return ResponseEntity.accepted().body(Map.of("run", Map.of(
                "id", run.getId(),
                "status", run.getStatus().name(),
                "workspaceId", run.getWorkspaceId(),
                "dateRangeStart", run.getDateRangeStart(),
                "dateRangeEnd", run.getDateRangeEnd(),
                "entriesProcessed", run.getEntriesProcessed())));
    }

    public record IngestRequest(String dateRangeStart, String dateRangeEnd) {}
}
