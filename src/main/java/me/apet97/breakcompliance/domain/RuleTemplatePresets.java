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
            "Custom (editable defaults)",
            "Custom policy (editable)",
            "Neutral starter. All thresholds are placeholders; admins edit them to match company policy. Pair with the Custom Policy settings tab to override the template thresholds without editing rows.",
            5,
            240,
            15,
            240,
            null,
            null,
            true,
            5);

    /**
     * Germany — Arbeitszeitgesetz (ArbZG). The relevant sections this
     * preset encodes:
     * <ul>
     *   <li><b>ArbZG §4</b> — rest breaks: &gt;6 h work → at least 30 min total;
     *       &gt;9 h work → at least 45 min total. Each individual break segment
     *       must be at least 15 minutes to count.</li>
     *   <li><b>ArbZG §3</b> — daily work cap: 8 h standard, up to 10 h allowed
     *       provided the 6-month rolling average stays ≤ 8 h/day. The 10 h
     *       hard cap is not encoded here yet — admins should flag daily totals
     *       over 600 min via {@code fallbackDetectionEnabled} or workspace
     *       custom policy.</li>
     * </ul>
     * The 2022 BAG ruling and the Ministry of Labour draft amendment require
     * employers to record full daily working hours within 7 days of the day
     * worked; failure can incur fines up to €30,000. This add-on supplies
     * the recording surface — admins are responsible for the
     * Continuous-Compliance procedure end-to-end.
     */
    public static final Preset GERMANY_ARBZG_STYLE = new Preset(
            "germany-arbzg-style",
            "Germany (ArbZG §3 + §4)",
            "Germany ArbZG (Arbeitszeitgesetz) §3 + §4",
            "German Working Time Act starter. ArbZG §4: >6h work → 30 min break; >9h → 45 min total; each segment ≥15 min. ArbZG §3: 10h daily hard cap, 6-month avg ≤8h/day. Editable. Not legal advice — admins must verify with their own counsel.",
            15,
            360,
            30,
            360,
            540,
            45,
            true,
            5);

    /**
     * California — Industrial Welfare Commission (IWC) Wage Orders. The
     * relevant rules this preset encodes:
     * <ul>
     *   <li>Meal break: 30 minutes uninterrupted before the 5th hour of work.
     *       A second 30-minute meal break is required before the 10th hour.</li>
     *   <li>Rest break: 10 paid minutes per 4 hours worked (not enforced by
     *       this preset; encode separately if your workspace tracks rest
     *       segments).</li>
     *   <li>Penalty: one hour of pay at the regular rate per missed meal or
     *       rest period (the "one-hour premium").</li>
     * </ul>
     * Meal breaks must be 30 continuous minutes — {@code allowSplitBreaks=false}.
     */
    public static final Preset CALIFORNIA_STYLE = new Preset(
            "california-style",
            "California (IWC meal/rest)",
            "California meal/rest (IWC Wage Orders)",
            "California Industrial Welfare Commission starter. Meal: 30 min uninterrupted before 5th hour; second meal before 10th. One-hour premium per missed period. Rest breaks (10 min / 4 h) not enforced here. Editable. Not legal advice — admins must verify with their own counsel.",
            10,
            300,
            30,
            300,
            600,
            30,
            false,
            5);

    /**
     * Display / seed order — CUSTOM_BASIC first so admins land on the
     * editable starter rather than a jurisdiction template they may not
     * use. Subsequent entries are alphabetical.
     */
    public static final List<Preset> ALL = List.of(CUSTOM_BASIC, CALIFORNIA_STYLE, GERMANY_ARBZG_STYLE);

    /**
     * Look up a preset by its user-visible {@code manifestLabel} — the
     * string the Clockify settings UI renders in the dropdown and echoes
     * back on {@code SETTINGS_UPDATED}. Returns null when the label is
     * unknown (the lifecycle handler treats that as a no-op so a stale
     * pushed value can't poison the workspace's settings row).
     */
    public static Preset fromManifestLabel(String label) {
        if (label == null) return null;
        for (Preset p : ALL) {
            if (p.manifestLabel().equals(label)) return p;
        }
        return null;
    }

    public record Preset(
            String key,
            String manifestLabel,
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
