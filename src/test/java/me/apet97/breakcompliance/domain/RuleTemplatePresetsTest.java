package me.apet97.breakcompliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import me.apet97.breakcompliance.domain.RuleTemplatePresets.Preset;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.RuleTemplateType;
import org.junit.jupiter.api.Test;

class RuleTemplatePresetsTest {

    @Test
    void allThreePresetsExist_inDeclaredOrder() {
        // Order matters: custom-basic must appear FIRST so the native
        // structured-settings dropdown lands on the editable starter.
        assertThat(RuleTemplatePresets.ALL)
                .extracting(Preset::key)
                .containsExactly("custom-basic", "california-style", "germany-arbzg-style");
    }

    @Test
    void customBasic_matchesTsValues() {
        Preset p = RuleTemplatePresets.CUSTOM_BASIC;
        assertThat(p.key()).isEqualTo("custom-basic");
        assertThat(p.name()).isEqualTo("Custom policy (editable)");
        assertThat(p.minimumValidBreakSegmentMinutes()).isEqualTo(5);
        assertThat(p.workThresholdMinutes()).isEqualTo(240);
        assertThat(p.requiredBreakMinutes()).isEqualTo(15);
        assertThat(p.maxContinuousWorkMinutesBeforeBreak()).isEqualTo(240);
        assertThat(p.secondThresholdMinutes()).isNull();
        assertThat(p.secondRequiredBreakMinutes()).isNull();
        assertThat(p.allowSplitBreaks()).isTrue();
        assertThat(p.gracePeriodMinutes()).isEqualTo(5);
    }

    @Test
    void germanyArbzgStyle_matchesArbZGSection4Thresholds() {
        Preset p = RuleTemplatePresets.GERMANY_ARBZG_STYLE;
        assertThat(p.key()).isEqualTo("germany-arbzg-style");
        assertThat(p.name()).isEqualTo("Germany ArbZG (Arbeitszeitgesetz) §3 + §4");
        // ArbZG §4: 6h → 30 min; 9h → 45 min; ≥15 min segments to count.
        assertThat(p.minimumValidBreakSegmentMinutes()).isEqualTo(15);
        assertThat(p.workThresholdMinutes()).isEqualTo(360);
        assertThat(p.requiredBreakMinutes()).isEqualTo(30);
        assertThat(p.maxContinuousWorkMinutesBeforeBreak()).isEqualTo(360);
        assertThat(p.secondThresholdMinutes()).isEqualTo(540);
        assertThat(p.secondRequiredBreakMinutes()).isEqualTo(45);
        assertThat(p.allowSplitBreaks()).isTrue();
        assertThat(p.gracePeriodMinutes()).isEqualTo(5);
    }

    @Test
    void californiaStyle_matchesIwcMealPeriodThresholds() {
        Preset p = RuleTemplatePresets.CALIFORNIA_STYLE;
        assertThat(p.key()).isEqualTo("california-style");
        assertThat(p.name()).isEqualTo("California meal/rest (IWC Wage Orders)");
        assertThat(p.minimumValidBreakSegmentMinutes()).isEqualTo(10);
        // IWC: meal before 5h (300 min); second meal before 10h (600 min).
        assertThat(p.workThresholdMinutes()).isEqualTo(300);
        assertThat(p.requiredBreakMinutes()).isEqualTo(30);
        assertThat(p.maxContinuousWorkMinutesBeforeBreak()).isEqualTo(300);
        assertThat(p.secondThresholdMinutes()).isEqualTo(600);
        assertThat(p.secondRequiredBreakMinutes()).isEqualTo(30);
        // CA meal break must be 30 continuous minutes — split is not allowed.
        assertThat(p.allowSplitBreaks()).isFalse();
        assertThat(p.gracePeriodMinutes()).isEqualTo(5);
    }

    @Test
    void toEntity_mapsAllFieldsAndMintsWorkspaceScopedId() {
        Preset p = RuleTemplatePresets.GERMANY_ARBZG_STYLE;
        Instant now = Instant.parse("2026-05-10T00:00:00Z");

        RuleTemplate t = p.toEntity("ws-test", now);

        assertThat(t.getWorkspaceId()).isEqualTo("ws-test");
        assertThat(t.getId()).isEqualTo("tpl_ws-test_germany-arbzg-style");
        assertThat(t.getKey()).isEqualTo("germany-arbzg-style");
        assertThat(t.getType()).isEqualTo(RuleTemplateType.BUILT_IN);
        assertThat(t.getPresetKey()).isEqualTo("germany-arbzg-style");
        assertThat(t.getVersion()).isEqualTo(1);
        assertThat(t.isEnabled()).isTrue();
        assertThat(t.getMinimumValidBreakSegmentMinutes()).isEqualTo(15);
        assertThat(t.getWorkThresholdMinutes()).isEqualTo(360);
        assertThat(t.getRequiredBreakMinutes()).isEqualTo(30);
        assertThat(t.getMaxContinuousWorkMinutesBeforeBreak()).isEqualTo(360);
        assertThat(t.getSecondThresholdMinutes()).isEqualTo(540);
        assertThat(t.getSecondRequiredBreakMinutes()).isEqualTo(45);
        assertThat(t.isAllowSplitBreaks()).isTrue();
        assertThat(t.getGracePeriodMinutes()).isEqualTo(5);
        assertThat(t.getCreatedAt()).isEqualTo(now);
        assertThat(t.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void toEntity_customBasicHasNullSecondThresholds() {
        RuleTemplate t = RuleTemplatePresets.CUSTOM_BASIC.toEntity("ws-x", Instant.now());
        assertThat(t.getSecondThresholdMinutes()).isNull();
        assertThat(t.getSecondRequiredBreakMinutes()).isNull();
    }

    @Test
    void customBasic_isFirstInDisplayOrder() {
        // Belt + suspenders for the dropdown rendering contract: the sidebar
        // JS keeps the API order; the native settings page uses the manifest
        // allowedValues order. Both default to custom-basic.
        assertThat(RuleTemplatePresets.ALL.get(0).key()).isEqualTo("custom-basic");
    }

    @Test
    void manifestLabels_areHumanReadable() {
        assertThat(RuleTemplatePresets.CUSTOM_BASIC.manifestLabel())
                .isEqualTo("Custom (Editable Defaults)");
        assertThat(RuleTemplatePresets.CALIFORNIA_STYLE.manifestLabel())
                .isEqualTo("California (IWC Meal & Rest)");
        assertThat(RuleTemplatePresets.GERMANY_ARBZG_STYLE.manifestLabel())
                .isEqualTo("Germany (ArbZG §3 & §4)");
    }

    @Test
    void fromManifestLabel_roundTripsForEveryPreset() {
        for (Preset p : RuleTemplatePresets.ALL) {
            assertThat(RuleTemplatePresets.fromManifestLabel(p.manifestLabel()))
                    .as("round-trip %s", p.key())
                    .isEqualTo(p);
        }
    }

    @Test
    void fromManifestLabel_returnsNullForUnknownOrBlank() {
        assertThat(RuleTemplatePresets.fromManifestLabel(null)).isNull();
        assertThat(RuleTemplatePresets.fromManifestLabel("")).isNull();
        assertThat(RuleTemplatePresets.fromManifestLabel("not-a-preset")).isNull();
        // Slug-form is intentionally NOT matched here — lookup is by the
        // user-visible label only. Slug compatibility lives in
        // InstallationService.extractPresetKey as a defensive fallback.
        assertThat(RuleTemplatePresets.fromManifestLabel("custom-basic")).isNull();
    }
}
