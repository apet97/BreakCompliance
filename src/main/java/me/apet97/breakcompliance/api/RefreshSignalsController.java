package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
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

    @PostMapping(value = "/api/refresh-signals/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> run(HttpServletRequest request, @RequestBody Map<String, String> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.requireAdmin(claims);
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(
                body.get("dateRangeStart"), body.get("dateRangeEnd"));
        LocalDate from = range.from();
        LocalDate to = range.to();
        ingestion.ingest(claims.workspaceId(), from, to, claims.reportsUrl());
        var emitted = findings.evaluateAndReplace(claims.workspaceId(), from, to);
        return ResponseEntity.ok(Map.of(
                "findingsCount", emitted.size(),
                "dateRangeStart", from.toString(),
                "dateRangeEnd", to.toString()));
    }

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
