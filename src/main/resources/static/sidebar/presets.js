export const PRESET_LABELS = {
    "custom-basic": "Custom (Editable Defaults)",
    "california-style": "California (IWC Meal & Rest)",
    "germany-arbzg-style": "Germany (ArbZG §3 & §4)",
};

export const TIMEZONE_LABELS = {
    "ENTRY_TIMEZONE": "Entry timezone",
    "UTC": "UTC",
};

export function fieldsThatDivergeFromPreset(active, preset) {
    if (!active || !preset?.thresholds) return [];
    const t = preset.thresholds;
    const diff = [];
    const compare = (name, a, b) => { if ((a ?? null) !== (b ?? null)) diff.push(name); };
    compare("workThresholdMinutes",       active.workThresholdMinutes,       t.workThresholdMinutes);
    compare("breakThresholdMinutes",      active.breakThresholdMinutes,      t.breakThresholdMinutes);
    compare("minBreakSegmentMinutes",     active.minBreakSegmentMinutes,     t.minBreakSegmentMinutes);
    compare("maxContinuousWorkMinutes",   active.maxContinuousWorkMinutes,   t.maxContinuousWorkMinutes);
    compare("gracePeriodMinutes",         active.gracePeriodMinutes,         t.gracePeriodMinutes);
    compare("allowSplitBreaks",           active.allowSplitBreaks,           t.allowSplitBreaks);
    const z = v => (v == null || v === 0) ? null : v;
    compare("secondWorkThresholdMinutes",  z(active.secondWorkThresholdMinutes),  z(t.secondWorkThresholdMinutes));
    compare("secondBreakThresholdMinutes", z(active.secondBreakThresholdMinutes), z(t.secondBreakThresholdMinutes));
    return diff;
}
