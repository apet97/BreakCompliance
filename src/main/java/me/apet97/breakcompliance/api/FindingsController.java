package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.Finding;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/findings")
public class FindingsController {

    private final FindingsService findingsService;

    public FindingsController(FindingsService findingsService) {
        this.findingsService = findingsService;
    }

    @PostMapping(value = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> evaluate(
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
        List<Finding> findings = findingsService.evaluateAndReplace(claims.workspaceId(), from, to);
        return ResponseEntity.ok(Map.of(
                "findingsCreated", findings.size(),
                "dateRangeStart", from.toString(),
                "dateRangeEnd", to.toString()));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            HttpServletRequest request,
            @RequestParam("dateRangeStart") String fromIso,
            @RequestParam("dateRangeEnd") String toIso) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(fromIso, toIso);
        LocalDate from = range.from();
        LocalDate to = range.to();
        List<Finding> findings = findingsService.list(claims.workspaceId(), from, to);
        List<Map<String, Object>> body = findings.stream().map(this::toJsonShape).toList();
        return ResponseEntity.ok(Map.of("findings", body));
    }

    private Map<String, Object> toJsonShape(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("workspaceId", f.getWorkspaceId());
        m.put("userId", f.getUserId());
        m.put("userName", f.getUserName());
        m.put("date", f.getDate().toString());
        m.put("templateId", f.getTemplateId());
        m.put("severity", f.getSeverity().name());
        m.put("code", f.getCode().name());
        m.put("message", f.getMessage());
        m.put("evidence", f.getEvidence());
        m.put("createdAt", f.getCreatedAt().toString());
        return m;
    }
}
