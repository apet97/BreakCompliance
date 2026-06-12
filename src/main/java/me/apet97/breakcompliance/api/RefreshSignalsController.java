package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefreshSignalsController {

    private final RefreshSignalRepository signals;
    private final IngestionService ingestion;
    private final FindingsService findings;

    public RefreshSignalsController(
            RefreshSignalRepository signals, IngestionService ingestion, FindingsService findings) {
        this.signals = signals;
        this.ingestion = ingestion;
        this.findings = findings;
    }

    @GetMapping("/api/refresh-signals")
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(signals.findByWorkspaceIdOrderByReceivedAtDesc(claims.workspaceId()).stream()
                .map(this::toBody)
                .toList());
    }

    /**
     * Trigger an admin-initiated refresh: ingest + re-evaluate findings
     * for the requested date range. Previously synchronous (blocked the
     * request thread for 30s+ on a large window); now mirrors
     * {@code IngestionController}'s 202+poll pattern so the sidebar can
     * surface progress without forcing the user to wait. The post-finalize
     * findings re-evaluation runs on {@code ingestExecutor} once the
     * ingest finishes, so a single click ends up with both fresh entries
     * and fresh findings without a second round-trip.
     */
    @PostMapping(value = "/api/refresh-signals/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> run(HttpServletRequest request, @RequestBody RefreshRunRequest body) {
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
        String workspaceId = claims.workspaceId();
        IngestionRun run;
        try {
            run = ingestion.beginAsyncForRefresh(
                    workspaceId,
                    from,
                    to,
                    claims.reportsUrl(),
                    runId -> findings.evaluateAndReplace(workspaceId, from, to));
        } catch (InstallationInactiveException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "installation_inactive",
                    "message", "This workspace's add-on is currently inactive. Re-enable it from Workspace Settings → Add-ons → Break Compliance to run compliance checks."));
        } catch (IngestionRunInProgressException e) {
            // The consumer or another admin click already kicked off an
            // ingest for this window. Don't spawn a duplicate; point the
            // sidebar at the live run.
            return ResponseEntity.status(409).body(Map.of(
                    "error", "ingest_in_progress",
                    "existingRunId", e.existingRunId(),
                    "pollUrl", "/api/ingest/runs/" + e.existingRunId(),
                    "message", "An ingest is already running for this workspace and date range; reuse the existing run."));
        }
        Map<String, Object> runBody = new LinkedHashMap<>();
        runBody.put("id", run.getId());
        runBody.put("status", run.getStatus().name());
        runBody.put("workspaceId", run.getWorkspaceId());
        runBody.put("dateRangeStart", run.getDateRangeStart());
        runBody.put("dateRangeEnd", run.getDateRangeEnd());
        runBody.put("entriesProcessed", run.getEntriesProcessed());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run", runBody);
        response.put("pollUrl", "/api/ingest/runs/" + run.getId());
        response.put("dateRangeStart", from.toString());
        response.put("dateRangeEnd", to.toString());
        return ResponseEntity.accepted().body(response);
    }

    public record RefreshRunRequest(String dateRangeStart, String dateRangeEnd) {}

    private Map<String, Object> toBody(RefreshSignal s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", s.getId());
        body.put("workspaceId", s.getWorkspaceId());
        body.put("source", s.getSource().name());
        body.put("eventType", s.getEventType());
        body.put("receivedAt", s.getReceivedAt().toString());
        body.put("status", s.getStatus().name());
        return body;
    }
}
