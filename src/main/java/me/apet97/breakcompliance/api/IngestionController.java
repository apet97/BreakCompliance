package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.clockify.ClockifyApiException;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    @PostMapping(value = "/detailed-report", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingest(
            HttpServletRequest request, @RequestBody Map<String, String> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.requireAdmin(claims);
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(
                body.get("dateRangeStart"), body.get("dateRangeEnd"));
        LocalDate from = range.from();
        LocalDate to = range.to();
        IngestionRun run;
        try {
            run = service.ingest(claims.workspaceId(), from, to, claims.reportsUrl());
        } catch (ClockifyApiException e) {
            // The Clockify developer portal returns 401 for the detailed-report
            // endpoint — surface it as a structured, non-fatal 503 so the
            // sidebar can render a friendly banner instead of "ingestion failed:
            // ClockifyApiException". Production workspaces with reports access
            // will not hit this branch. Other ClockifyApiException statuses keep
            // the legacy FAILED-recorded-in-body path (controller-level rethrow).
            if (e.statusCode() == 401) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "reports_unavailable",
                        "message", "Reports API is unavailable in the Clockify developer environment. Install in a production Clockify workspace to run full compliance checks."));
            }
            throw e;
        }
        return ResponseEntity.ok(Map.of("run", Map.of(
                "id", run.getId(),
                "status", run.getStatus().name(),
                "entriesProcessed", run.getEntriesProcessed(),
                "errorCode", run.getErrorCode() == null ? "" : run.getErrorCode())));
    }
}
