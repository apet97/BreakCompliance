package me.apet97.breakcompliance.persistence.entities;

public enum TimezoneStrategy {
    /**
     * Bucket each time entry by the day in the entry's own recorded
     * timezone ({@code timeInterval.timeZone}). Recommended for
     * distributed teams — every shift lands on the day the worker
     * actually worked it.
     */
    ENTRY_TIMEZONE("Use entry's local time zone"),

    /**
     * Bucket every entry by UTC calendar date. Useful when the workspace
     * needs a single canonical day boundary regardless of where the user
     * logged time. Trade-off: a worker in UTC+10 finishing at 02:00 local
     * lands on the previous UTC day, which may surprise admins.
     */
    UTC("Use UTC for every entry");

    private final String manifestLabel;

    TimezoneStrategy(String manifestLabel) {
        this.manifestLabel = manifestLabel;
    }

    public String manifestLabel() {
        return manifestLabel;
    }

    /**
     * Map an inbound manifest-dropdown value (the user-visible string the
     * Clockify settings UI emits on {@code SETTINGS_UPDATED}) back to the
     * enum. Falls through to {@link #valueOf(String)} so legacy raw-enum
     * deliveries (e.g. {@code "ENTRY_TIMEZONE"}) still parse — useful when
     * Clockify retries a setting it cached before this rename shipped.
     * Returns null when no match.
     */
    public static TimezoneStrategy fromManifestLabel(String label) {
        if (label == null || label.isBlank()) return null;
        for (TimezoneStrategy s : values()) {
            if (s.manifestLabel.equals(label)) return s;
        }
        try {
            return TimezoneStrategy.valueOf(label);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
