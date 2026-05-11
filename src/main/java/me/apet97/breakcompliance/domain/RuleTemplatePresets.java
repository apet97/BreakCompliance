package me.apet97.breakcompliance.domain;

import java.time.Instant;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;

/**
 * Built-in rule template presets. Code-defined rather than SQL-seeded so the
 * lifecycle of each preset row matches the workspace it belongs to — the
 * service layer inserts these on first read per workspace, ensuring deletion
 * of a workspace removes the presets that were minted for it.
 */
public final class RuleTemplatePresets {

    private RuleTemplatePresets() {
    }

    public static final Preset CUSTOM_BASIC = new Preset(
            "custom-basic",
            "Custom basic policy",
            "Neutral starter template. All thresholds are placeholders intended to be edited by an admin to match company policy.",
            5,
            240,
            15,
            240,
            null,
            null,
            true,
            5);

    public static final Preset GERMANY_ARBG_STYLE = new Preset(
            "germany-arbg-style",
            "Germany ArbZG-style starter",
            "Starter template inspired by the structure of Germany's Arbeitszeitgesetz break thresholds. Editable. Not legal advice — admins must verify with their own counsel.",
            15,
            360,
            30,
            360,
            540,
            45,
            true,
            5);

    public static final Preset CALIFORNIA_STYLE = new Preset(
            "california-style",
            "California-style starter",
            "Starter template inspired by the structure of California meal/rest period thresholds. Editable. Not legal advice — admins must verify with their own counsel.",
            10,
            300,
            30,
            300,
            600,
            30,
            false,
            5);

    public static final List<Preset> ALL = List.of(CUSTOM_BASIC, GERMANY_ARBG_STYLE, CALIFORNIA_STYLE);

    public record Preset(
            String key,
            String name,
            String description,
            int minimumValidBreakSegmentMinutes,
            int workThresholdMinutes,
            int requiredBreakMinutes,
            int maxContinuousWorkMinutesBeforeBreak,
            Integer secondThresholdMinutes,
            Integer secondRequiredBreakMinutes,
            boolean allowSplitBreaks,
            int gracePeriodMinutes) {

        public RuleTemplate toEntity(String workspaceId, Instant now) {
            RuleTemplate t = new RuleTemplate();
            t.setWorkspaceId(workspaceId);
            t.setId("tpl_" + workspaceId + "_" + key);
            t.setKey(key);
            t.setName(name);
            t.setDescription(description);
            t.setType(RuleTemplateType.BUILT_IN);
            t.setPresetKey(key);
            t.setVersion(1);
            t.setEnabled(true);
            t.setMinimumValidBreakSegmentMinutes(minimumValidBreakSegmentMinutes);
            t.setWorkThresholdMinutes(workThresholdMinutes);
            t.setRequiredBreakMinutes(requiredBreakMinutes);
            t.setMaxContinuousWorkMinutesBeforeBreak(maxContinuousWorkMinutesBeforeBreak);
            t.setSecondThresholdMinutes(secondThresholdMinutes);
            t.setSecondRequiredBreakMinutes(secondRequiredBreakMinutes);
            t.setAllowSplitBreaks(allowSplitBreaks);
            t.setGracePeriodMinutes(gracePeriodMinutes);
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            return t;
        }
    }
}
