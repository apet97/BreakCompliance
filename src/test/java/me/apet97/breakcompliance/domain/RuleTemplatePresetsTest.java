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
        assertThat(RuleTemplatePresets.ALL)
                .extracting(Preset::key)
                .containsExactly("custom-basic", "germany-arbg-style", "california-style");
    }

    @Test
    void customBasic_matchesTsValues() {
        Preset p = RuleTemplatePresets.CUSTOM_BASIC;
        assertThat(p.key()).isEqualTo("custom-basic");
        assertThat(p.name()).isEqualTo("Custom basic policy");
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
    void germanyArbgStyle_matchesTsValues() {
        Preset p = RuleTemplatePresets.GERMANY_ARBG_STYLE;
        assertThat(p.key()).isEqualTo("germany-arbg-style");
        assertThat(p.name()).isEqualTo("Germany ArbZG-style starter");
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
    void californiaStyle_matchesTsValues() {
        Preset p = RuleTemplatePresets.CALIFORNIA_STYLE;
        assertThat(p.key()).isEqualTo("california-style");
        assertThat(p.name()).isEqualTo("California-style starter");
        assertThat(p.minimumValidBreakSegmentMinutes()).isEqualTo(10);
        assertThat(p.workThresholdMinutes()).isEqualTo(300);
        assertThat(p.requiredBreakMinutes()).isEqualTo(30);
        assertThat(p.maxContinuousWorkMinutesBeforeBreak()).isEqualTo(300);
        assertThat(p.secondThresholdMinutes()).isEqualTo(600);
        assertThat(p.secondRequiredBreakMinutes()).isEqualTo(30);
        assertThat(p.allowSplitBreaks()).isFalse();
        assertThat(p.gracePeriodMinutes()).isEqualTo(5);
    }

    @Test
    void toEntity_mapsAllFieldsAndMintsWorkspaceScopedId() {
        Preset p = RuleTemplatePresets.GERMANY_ARBG_STYLE;
        Instant now = Instant.parse("2026-05-10T00:00:00Z");

        RuleTemplate t = p.toEntity("ws-test", now);

        assertThat(t.getWorkspaceId()).isEqualTo("ws-test");
        assertThat(t.getId()).isEqualTo("tpl_ws-test_germany-arbg-style");
        assertThat(t.getKey()).isEqualTo("germany-arbg-style");
        assertThat(t.getType()).isEqualTo(RuleTemplateType.BUILT_IN);
        assertThat(t.getPresetKey()).isEqualTo("germany-arbg-style");
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
}
