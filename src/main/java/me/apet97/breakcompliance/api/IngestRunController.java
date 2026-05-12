package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Polled by the sidebar to track an async ingest started via
 * {@code POST /api/ingest/detailed-report}. Returns the current state of
 * the run — status, entries processed so far (updated on finalize), and
 * any captured errorCode for the failure path. Scoped to the JWT's
 * workspace; a run from a different workspace returns 404 even if the id
 * is guessed correctly.
 */
@RestController
public class IngestRunController {

    private final IngestionRunRepository runRepo;

    public IngestRunController(IngestionRunRepository runRepo) {
        this.runRepo = runRepo;
    }

    /**
     * Most recent COMPLETED run for the workspace, ordered by
     * {@code completedAt} desc. Drives the sidebar's "Refreshed Xm ago"
     * staleness indicator so a fresh sidebar open knows when data last
     * landed without forcing the user to click anything. Returns 204 No
     * Content when no completed run exists yet (fresh install, or every
     * prior run is RUNNING / FAILED).
     *
     * <p>The path is declared before {@code /api/ingest/runs/{runId}} so
     * Spring's routing matches the literal {@code latest} first instead
     * of trying to look up a row with id "latest". Both are GETs on the
     * same prefix so the order matters.
     */
    @GetMapping("/api/ingest/runs/latest")
    public ResponseEntity<Map<String, Object>> getLatestCompletedRun(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        return runRepo
                .findFirstByWorkspaceIdAndStatusOrderByCompletedAtDesc(
                        claims.workspaceId(), IngestionStatus.COMPLETED)
                .<ResponseEntity<Map<String, Object>>>map(run -> ResponseEntity.ok(toBody(run)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/ingest/runs/{runId}")
    public ResponseEntity<Map<String, Object>> getRun(
            HttpServletRequest request, @PathVariable("runId") String runId) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        return runRepo
                .findById(new IngestionRun.Pk(claims.workspaceId(), runId))
                .<ResponseEntity<Map<String, Object>>>map(run -> ResponseEntity.ok(toBody(run)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "ingest_run_not_found")));
    }

    private static Map<String, Object> toBody(IngestionRun run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", run.getId());
        body.put("workspaceId", run.getWorkspaceId());
        body.put("status", run.getStatus().name());
        body.put("entriesProcessed", run.getEntriesProcessed());
        body.put("dateRangeStart", run.getDateRangeStart());
        body.put("dateRangeEnd", run.getDateRangeEnd());
        body.put("errorCode", run.getErrorCode() == null ? "" : run.getErrorCode());
        body.put("createdAt", run.getCreatedAt() == null ? null : run.getCreatedAt().toString());
        body.put("completedAt", run.getCompletedAt() == null ? null : run.getCompletedAt().toString());
        return body;
    }
}
