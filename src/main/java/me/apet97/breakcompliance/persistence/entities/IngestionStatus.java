package me.apet97.breakcompliance.persistence.entities;

public enum IngestionStatus {
    /**
     * Set by the prepare phase before the long-running Clockify HTTP call.
     * If the JVM dies between prepare and finalize, the row stays in
     * RUNNING so operators can identify stuck runs.
     */
    RUNNING,
    COMPLETED,
    FAILED
}
