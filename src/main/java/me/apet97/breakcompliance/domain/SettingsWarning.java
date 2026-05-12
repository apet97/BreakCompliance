package me.apet97.breakcompliance.domain;

import java.util.ArrayList;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;

/**
 * Cross-field warning surfaced when admin-saved thresholds are internally
 * inconsistent. Validation runs at {@code SETTINGS_UPDATED} time and the
 * results persist on the workspace row so the sidebar can render them on
 * every session without re-running the checks.
 *
 * <p>The engine still falls back to {@link RuleTemplatePresets#CUSTOM_BASIC}
 * defaults for any null/≤0 threshold; these warnings exist to explain the
 * fallback rather than to reject the save (Clockify would just retry).
 */
public record SettingsWarning(String code, String message) {

    /**
     * Run every cross-field check against {@code settings} and return the
     * warnings produced. Empty list = the configuration is internally
     * consistent (it may still be unusual, but the engine will not have
     * to silently substitute fallback values for nonsensical inputs).
     */
    public static List<SettingsWarning> validate(WorkspaceSettings settings) {
        List<SettingsWarning> warnings = new ArrayList<>();
        Integer work = settings.getCustomWorkThresholdMinutes();
        Integer req = settings.getCustomBreakThresholdMinutes();
        Integer segment = settings.getCustomMinBreakSegmentMinutes();
        Integer grace = settings.getCustomGracePeriodMinutes();
        Integer secondWork = settings.getCustomSecondWorkThresholdMinutes();
        Integer secondReq = settings.getCustomSecondBreakThresholdMinutes();

        if (positive(req) && positive(work) && req > work) {
            warnings.add(new SettingsWarning(
                    "break_exceeds_work",
                    "Required break (" + req + " min) is longer than the work threshold ("
                            + work + " min). Every qualifying shift will be flagged as missing a break."));
        }

        if (positive(segment) && positive(req) && segment > req) {
            warnings.add(new SettingsWarning(
                    "segment_exceeds_required",
                    "Min break segment (" + segment + " min) is longer than the required break ("
                            + req + " min). No single segment can ever satisfy the requirement."));
        }

        if (positive(secondWork) && positive(work) && secondWork <= work) {
            warnings.add(new SettingsWarning(
                    "second_tier_not_longer",
                    "Second-tier work threshold (" + secondWork + " min) must be longer than the first-tier work threshold ("
                            + work + " min). The second tier will fire on every shift the first tier already covers."));
        }

        if (positive(secondReq) && !positive(secondWork)) {
            warnings.add(new SettingsWarning(
                    "second_tier_break_orphaned",
                    "Second-tier required break is set (" + secondReq + " min) but the second-tier work threshold is disabled. The extra break will never apply."));
        }

        if (positive(grace) && positive(work) && grace >= work) {
            warnings.add(new SettingsWarning(
                    "grace_swallows_work",
                    "Grace period (" + grace + " min) is at least as long as the work threshold ("
                            + work + " min). Compliance checks will never fire."));
        }

        return List.copyOf(warnings);
    }

    private static boolean positive(Integer i) {
        return i != null && i > 0;
    }
}
