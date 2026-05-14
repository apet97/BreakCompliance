package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.FindingReview;
import me.apet97.breakcompliance.persistence.entities.ReviewStatus;
import me.apet97.breakcompliance.persistence.repositories.FindingReviewRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final FindingReviewRepository reviewRepo;
    private final AuditService audit;

    public FindingsController(
            FindingsService findingsService, FindingReviewRepository reviewRepo, AuditService audit) {
        this.findingsService = findingsService;
        this.reviewRepo = reviewRepo;
        this.audit = audit;
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
            @RequestParam("dateRangeEnd") String toIso,
            @RequestParam(value = "openOnly", required = false, defaultValue = "false") boolean openOnly,
            @RequestParam(value = "userIds", required = false) String userIdsCsv) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(fromIso, toIso);
        LocalDate from = range.from();
        LocalDate to = range.to();
        List<Finding> findings = findingsService.list(claims.workspaceId(), from, to);
        Map<String, FindingReview> reviewsById = loadReviewsFor(claims.workspaceId(), findings);
        // P2.8 — "All open findings" workflow filters out anything an admin
        // already acknowledged or overrode. A finding with no review row
        // is implicitly OPEN; explicit OPEN review rows count too.
        if (openOnly) {
            findings = findings.stream()
                    .filter(f -> {
                        FindingReview r = reviewsById.get(f.getId());
                        return r == null || r.getStatus() == ReviewStatus.OPEN;
                    })
                    .toList();
        }
        // P2.1 — server-side userIds filter: comma-separated allowlist.
        // Blank / missing param means "all users." Filter runs after openOnly
        // so the two compose without surprise.
        java.util.Set<String> userIdAllowlist = parseUserIds(userIdsCsv);
        if (!userIdAllowlist.isEmpty()) {
            findings = findings.stream()
                    .filter(f -> userIdAllowlist.contains(f.getUserId()))
                    .toList();
        }
        List<Map<String, Object>> body = findings.stream()
                .map(f -> toJsonShape(f, reviewsById.get(f.getId())))
                .toList();
        return ResponseEntity.ok(Map.of("findings", body));
    }

    private static java.util.Set<String> parseUserIds(String csv) {
        if (csv == null || csv.isBlank()) return java.util.Set.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
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
            @RequestParam(value = "format", required = false, defaultValue = "csv") String format,
            @RequestParam(value = "userIds", required = false) String userIdsCsv) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        if (!"csv".equalsIgnoreCase(format)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported export format: " + format);
        }
        RequestValidator.DateRange range = RequestValidator.parseAndValidateDates(fromIso, toIso);
        LocalDate from = range.from();
        LocalDate to = range.to();
        List<Finding> findings = findingsService.list(claims.workspaceId(), from, to);
        // P2.1 — same userIds filter as the JSON list endpoint so the
        // exported CSV matches the rendered page.
        java.util.Set<String> userIdAllowlist = parseUserIds(userIdsCsv);
        if (!userIdAllowlist.isEmpty()) {
            findings = findings.stream()
                    .filter(f -> userIdAllowlist.contains(f.getUserId()))
                    .toList();
        }
        String csv = toCsv(findings);
        String filename = "break-compliance-" + claims.workspaceId() + "-" + from + "-" + to + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }

    /**
     * Upsert the review row for a single finding. OPEN clears any prior
     * note (callers shouldn't carry forward audit text across re-opens);
     * ACKNOWLEDGED / OVERRIDDEN persist the optional free-text note. The
     * workspace scope from the JWT is the authoritative tenant — the
     * finding must already belong to it or the call 404s, so an admin
     * can't review another workspace's data by guessing a finding id.
     * Admin-gated: review is an audit-state write.
     */
    @PostMapping(value = "/{findingId}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> review(
            HttpServletRequest request,
            @PathVariable("findingId") String findingId,
            @RequestBody Map<String, String> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.requireAdmin(claims);
        ReviewStatus status = parseReviewStatus(body.get("status"));
        String note = body.get("note");
        // Existence check against the finding lives on the service so the
        // workspace-scope filter is reused (and we get a clean 404 instead
        // of letting a phantom review row leak in).
        if (!findingsService.exists(claims.workspaceId(), findingId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found in this workspace.");
        }
        FindingReview review = reviewRepo
                .findById(new FindingReview.Pk(claims.workspaceId(), findingId))
                .orElseGet(() -> {
                    FindingReview r = new FindingReview();
                    r.setWorkspaceId(claims.workspaceId());
                    r.setFindingId(findingId);
                    return r;
                });
        review.setStatus(status);
        // Empty / blank → store as null so the column doesn't accumulate
        // whitespace-only audit text. OPEN drops any prior note.
        if (status == ReviewStatus.OPEN || note == null || note.isBlank()) {
            review.setNote(null);
        } else {
            review.setNote(note.trim());
        }
        review.setUpdatedAt(Instant.now());
        reviewRepo.saveAndFlush(review);
        // P4.3 — record the admin's review action so the audit endpoint
        // can render a history. Actor is the JWT's user id; details are
        // bounded to status + whether a note exists (don't echo PII).
        audit.record(
                claims.workspaceId(),
                claims.userId(),
                "FINDING_REVIEW",
                "Finding",
                findingId,
                Map.of(
                        "status", status.name(),
                        "hasNote", review.getNote() != null && !review.getNote().isBlank()));
        return ResponseEntity.ok(toReviewShape(review));
    }

    private ReviewStatus parseReviewStatus(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing review status.");
        }
        try {
            return ReviewStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown review status. Expected OPEN, ACKNOWLEDGED, or OVERRIDDEN.");
        }
    }

    private Map<String, FindingReview> loadReviewsFor(String workspaceId, List<Finding> findings) {
        if (findings.isEmpty()) return Map.of();
        // Workspace-scoped lookup + in-memory filter to the current page of
        // findings keeps the query simple while still cheap — a single
        // workspace's reviews max out at the same order of magnitude as
        // its findings (one per finding), and the JpaRepository already
        // indexes on the workspace_id column via the composite PK.
        return reviewRepo.findByWorkspaceId(workspaceId).stream()
                .collect(Collectors.toMap(FindingReview::getFindingId, r -> r, (a, b) -> a, HashMap::new));
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

    private Map<String, Object> toReviewShape(FindingReview r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("findingId", r.getFindingId());
        body.put("status", r.getStatus().name());
        body.put("note", r.getNote());
        body.put("updatedAt", r.getUpdatedAt().toString());
        return body;
    }

    private Map<String, Object> toJsonShape(Finding f, FindingReview review) {
        Map<String, Object> m = toJsonShape(f);
        m.put("review", review == null ? null : toReviewShape(review));
        return m;
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
