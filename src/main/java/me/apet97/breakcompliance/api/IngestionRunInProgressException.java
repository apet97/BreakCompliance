package me.apet97.breakcompliance.api;

/**
 * Thrown by {@link IngestionService} when a controller or the
 * refresh-signal consumer requests an ingest for a (workspace,
 * date range) that already has a RUNNING {@link
 * me.apet97.breakcompliance.persistence.entities.IngestionRun}.
 * Carries the existing run's id so the controller can surface a
 * {@code 409 Conflict} with a pointer to the live poll URL —
 * cheaper and less surprising than spawning duplicate ingests when
 * an admin double-clicks "Refresh" or a webhook arrives concurrently.
 */
public class IngestionRunInProgressException extends RuntimeException {

    private final String existingRunId;

    public IngestionRunInProgressException(String existingRunId) {
        super("ingest already in flight; runId=" + existingRunId);
        this.existingRunId = existingRunId;
    }

    public String existingRunId() {
        return existingRunId;
    }
}
