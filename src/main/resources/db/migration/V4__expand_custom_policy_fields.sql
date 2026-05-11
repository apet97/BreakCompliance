-- Extend the custom break policy with granular admin-editable thresholds
-- that map 1:1 to RuleTemplate columns. When customPolicyEnabled=true,
-- BreakRuleEngine.applyCustomPolicyOverride uses each non-null value here
-- to override the corresponding field of the resolved template; nulls
-- fall through to the template's value.

ALTER TABLE breakcompliance_workspace_settings
    ADD COLUMN custom_min_break_segment_minutes        INTEGER,
    ADD COLUMN custom_max_continuous_work_minutes      INTEGER,
    ADD COLUMN custom_grace_period_minutes             INTEGER,
    ADD COLUMN custom_allow_split_breaks               BOOLEAN,
    ADD COLUMN custom_second_work_threshold_minutes    INTEGER,
    ADD COLUMN custom_second_break_threshold_minutes   INTEGER;
