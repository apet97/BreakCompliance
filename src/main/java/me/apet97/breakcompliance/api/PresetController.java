package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import me.apet97.breakcompliance.addon.lifecycle.InstallationService;
import me.apet97.breakcompliance.domain.RuleTemplatePresets;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sidebar-driven preset selection. Native structured-settings can't host
 * the dropdown because Clockify's UI never re-renders the threshold
 * fields after our backend applies a preset's values — the user would
 * have to reload the page to see anything change. Moving the chooser to
 * the iframe lets us preview each preset's thresholds inline, show a
 * "Matches preset" / "Customized" indicator, and confirm before
 * overwriting custom edits.
 *
 * <p>{@code GET /api/presets} returns the catalogue (label, statute link
 * via description, canonical thresholds) so the sidebar can render cards
 * and detect divergence client-side.
 *
 * <p>{@code POST /api/presets/apply} is admin-only and funnels through
 * {@link InstallationService#applyPreset}, which writes the threshold
 * columns + re-runs cross-field validation in the same transaction.
 */
@RestController
public class PresetController {

    private final InstallationService installationService;
    private final AuditService audit;

    public PresetController(InstallationService installationService, AuditService audit) {
        this.installationService = installationService;
        this.audit = audit;
    }

    @GetMapping("/api/presets")
    public ResponseEntity<Map<String, Object>> listPresets(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null) {
            return ResponseEntity.status(401).build();
        }
        List<Map<String, Object>> presets = new ArrayList<>();
        for (RuleTemplatePresets.Preset p : RuleTemplatePresets.ALL) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", p.key());
            entry.put("label", p.manifestLabel());
            entry.put("description", p.description());
            Map<String, Object> thresholds = new LinkedHashMap<>();
            thresholds.put("workThresholdMinutes", p.workThresholdMinutes());
            thresholds.put("breakThresholdMinutes", p.requiredBreakMinutes());
            thresholds.put("minBreakSegmentMinutes", p.minimumValidBreakSegmentMinutes());
            thresholds.put("maxContinuousWorkMinutes", p.maxContinuousWorkMinutesBeforeBreak());
            thresholds.put("gracePeriodMinutes", p.gracePeriodMinutes());
            thresholds.put("allowSplitBreaks", p.allowSplitBreaks());
            thresholds.put("secondWorkThresholdMinutes", p.secondThresholdMinutes());
            thresholds.put("secondBreakThresholdMinutes", p.secondRequiredBreakMinutes());
            entry.put("thresholds", thresholds);
            presets.add(entry);
        }
        return ResponseEntity.ok(Map.of("presets", presets));
    }

    @PostMapping("/api/presets/apply")
    public ResponseEntity<Map<String, Object>> apply(
            HttpServletRequest request, @RequestBody Map<String, String> body) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null || claims.workspaceId() == null) {
            return ResponseEntity.status(401).build();
        }
        RequestValidator.requireAdmin(claims);
        String presetKey = body == null ? null : body.get("presetKey");
        if (presetKey == null || presetKey.isBlank()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "missing_preset_key"));
        }
        WorkspaceSettings updated;
        try {
            updated = installationService.applyPreset(claims.workspaceId(), presetKey);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "unknown_preset_key",
                    "presetKey", presetKey));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "no_workspace_settings",
                    "message", "Re-open the add-on to initialise workspace settings, then retry."));
        }
        // P4.3 — record the preset apply so the audit endpoint can render
        // who changed which workspace's rules and when.
        audit.record(
                claims.workspaceId(),
                claims.userId(),
                "PRESET_APPLY",
                "WorkspaceSettings",
                claims.workspaceId(),
                Map.of("presetKey", presetKey));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("appliedPresetKey", updated.getAppliedPresetKey());
        response.put("workThresholdMinutes", updated.getCustomWorkThresholdMinutes());
        response.put("breakThresholdMinutes", updated.getCustomBreakThresholdMinutes());
        response.put("minBreakSegmentMinutes", updated.getCustomMinBreakSegmentMinutes());
        response.put("maxContinuousWorkMinutes", updated.getCustomMaxContinuousWorkMinutes());
        response.put("gracePeriodMinutes", updated.getCustomGracePeriodMinutes());
        response.put("allowSplitBreaks", updated.getCustomAllowSplitBreaks());
        response.put("secondWorkThresholdMinutes", updated.getCustomSecondWorkThresholdMinutes());
        response.put("secondBreakThresholdMinutes", updated.getCustomSecondBreakThresholdMinutes());
        return ResponseEntity.ok(response);
    }
}
