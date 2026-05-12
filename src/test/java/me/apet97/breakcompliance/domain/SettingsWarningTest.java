package me.apet97.breakcompliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import org.junit.jupiter.api.Test;

class SettingsWarningTest {

    private static WorkspaceSettings consistent() {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setCustomWorkThresholdMinutes(240);
        s.setCustomBreakThresholdMinutes(15);
        s.setCustomMinBreakSegmentMinutes(5);
        s.setCustomMaxContinuousWorkMinutes(240);
        s.setCustomGracePeriodMinutes(5);
        s.setCustomSecondWorkThresholdMinutes(0);
        s.setCustomSecondBreakThresholdMinutes(0);
        return s;
    }

    @Test
    void consistentSettings_produceNoWarnings() {
        assertThat(SettingsWarning.validate(consistent())).isEmpty();
    }

    @Test
    void breakLongerThanWork_isFlagged() {
        WorkspaceSettings s = consistent();
        s.setCustomBreakThresholdMinutes(600);
        List<SettingsWarning> warnings = SettingsWarning.validate(s);
        assertThat(warnings).extracting(SettingsWarning::code)
                .contains("break_exceeds_work");
    }

    @Test
    void segmentLongerThanRequiredBreak_isFlagged() {
        WorkspaceSettings s = consistent();
        s.setCustomBreakThresholdMinutes(15);
        s.setCustomMinBreakSegmentMinutes(20);
        assertThat(SettingsWarning.validate(s)).extracting(SettingsWarning::code)
                .contains("segment_exceeds_required");
    }

    @Test
    void secondTierShorterThanFirst_isFlagged() {
        WorkspaceSettings s = consistent();
        s.setCustomWorkThresholdMinutes(300);
        s.setCustomSecondWorkThresholdMinutes(200);
        s.setCustomSecondBreakThresholdMinutes(45);
        assertThat(SettingsWarning.validate(s)).extracting(SettingsWarning::code)
                .contains("second_tier_not_longer");
    }

    @Test
    void orphanSecondTierBreak_isFlagged() {
        WorkspaceSettings s = consistent();
        s.setCustomSecondWorkThresholdMinutes(0);
        s.setCustomSecondBreakThresholdMinutes(45);
        assertThat(SettingsWarning.validate(s)).extracting(SettingsWarning::code)
                .contains("second_tier_break_orphaned");
    }

    @Test
    void graceLargerThanWork_isFlagged() {
        WorkspaceSettings s = consistent();
        s.setCustomWorkThresholdMinutes(60);
        s.setCustomGracePeriodMinutes(60);
        assertThat(SettingsWarning.validate(s)).extracting(SettingsWarning::code)
                .contains("grace_swallows_work");
    }

    @Test
    void nullThresholds_produceNoWarningsForThatField() {
        // Engine fallback handles null; validator only flags positive,
        // inconsistent pairs so it doesn't warn on partially-configured
        // (newly-installed) workspaces.
        WorkspaceSettings s = new WorkspaceSettings();
        assertThat(SettingsWarning.validate(s)).isEmpty();
    }
}
