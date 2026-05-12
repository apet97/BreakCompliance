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

    public FindingsController(FindingsService findingsService, FindingReviewRepository reviewRepo) {
        this.findingsService = findingsService;
        this.reviewRepo = reviewRepo;
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
        Map<String, FindingReview> reviewsById = loadReviewsFor(claims.workspaceId(), findings);
        List<Map<String, Object>> body = findings.stream()
                .map(f -> toJsonShape(f, reviewsById.get(f.getId())))
                .toList();
        return ResponseEntity.ok(Map.of("findings", body));
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
