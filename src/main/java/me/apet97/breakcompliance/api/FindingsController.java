package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.Finding;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * Stream the workspace's findings for the given date range as CSV so
     * admins can hand compliance summaries to legal / HR without screen-
     * scraping the sidebar. Same auth + workspace scope as the JSON list
     * endpoint above — read-only, no admin gate, returns only the calling
     * workspace's data.
     *
     * <p>{@code format} is currently CSV-only; the param exists so future
     * formats (xlsx, tsv) can be added without changing the URL or
     * breaking sidebar deep-links. Any value other than {@code csv} (and
     * {@code null}) returns 400 instead of silently defaulting — fail
     * loudly on typos.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(
            HttpServletRequest request,
            @RequestParam("dateRangeStart") String fromIso,
            @RequestParam("dateRangeEnd") String toIso,
            @RequestParam(value = "format", required = false, defaultValue = "csv") String format) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        if (!"csv".equalsIgnoreCase(format)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Unsupported export format: " + format);
        }
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(fromIso, toIso);
        LocalDate from = range.from();
        LocalDate to = range.to();
        List<Finding> findings = findingsService.list(claims.workspaceId(), from, to);
        String csv = toCsv(findings);
        String filename = "break-compliance-" + claims.workspaceId() + "-" + from + "-" + to + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }

    private static final String CSV_HEADER =
            "date,userId,userName,severity,code,message,workMinutes,breakMinutes,syntheticBreakMinutes,templateId,createdAt";

    private static String toCsv(List<Finding> findings) {
        // RFC 4180 line endings. UTF-8 BOM intentionally omitted — Excel
        // for Mac honors the Content-Type charset; adding a BOM would
        // pollute the column names with U+FEFF for non-Excel consumers
        // (jq, csvkit, Python pandas) that already handle UTF-8 natively.
        StringBuilder sb = new StringBuilder(CSV_HEADER).append("\r\n");
        for (Finding f : findings) {
            Map<String, Object> ev = f.getEvidence();
            sb.append(csvCell(f.getDate().toString())).append(',')
              .append(csvCell(f.getUserId())).append(',')
              .append(csvCell(f.getUserName())).append(',')
              .append(csvCell(f.getSeverity().name())).append(',')
              .append(csvCell(f.getCode().name())).append(',')
              .append(csvCell(f.getMessage())).append(',')
              .append(csvCell(evidenceLong(ev, "workMinutes"))).append(',')
              .append(csvCell(evidenceLong(ev, "breakMinutes"))).append(',')
              .append(csvCell(evidenceLong(ev, "syntheticBreakMinutes"))).append(',')
              .append(csvCell(f.getTemplateId())).append(',')
              .append(csvCell(f.getCreatedAt().toString()))
              .append("\r\n");
        }
        return sb.toString();
    }

    /** RFC-4180 CSV escaping. Wraps in quotes whenever the cell contains
     *  a comma, quote, CR, or LF; doubles any embedded quote. */
    private static String csvCell(String value) {
        if (value == null) return "";
        boolean needsQuoting =
                value.indexOf(',') >= 0
                        || value.indexOf('"') >= 0
                        || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0;
        if (!needsQuoting) return value;
        StringBuilder b = new StringBuilder(value.length() + 2);
        b.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') b.append('"');
            b.append(c);
        }
        b.append('"');
        return b.toString();
    }

    /** Coerce a numeric evidence field to a stable string. Falsy values
     *  (null, 0, missing) emit "" so spreadsheets don't render a forest
     *  of zeros — empty cells are clearer for "no break recorded". */
    private static String evidenceLong(Map<String, Object> evidence, String key) {
        if (evidence == null) return "";
        Object raw = evidence.get(key);
        if (raw == null) return "";
        if (raw instanceof Number n) {
            long v = n.longValue();
            return v == 0 ? "" : Long.toString(v);
        }
        String s = raw.toString();
        return "0".equals(s) ? "" : s;
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
