package me.apet97.breakcompliance.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recovers from ingest runs orphaned by JVM/container restarts mid-flight.
 *
 * <p>The 3-phase ingest pipeline ({@link IngestionService#beginAsync})
 * commits the {@link IngestionRun} row in {@code RUNNING} before the
 * long Clockify HTTP call. If the executor crashes or the container
 * is killed before {@code finalize} writes the terminal status, the
 * row stays {@code RUNNING} forever — and the refresh-signal
 * consumer's dedupe check refuses to dispatch new ingests for the
 * same (workspace, date range) because that "in-flight" run blocks it.
 *
 * <p>The reaper runs on a configurable interval, marks any
 * {@code RUNNING} run whose {@code createdAt} exceeds the stuck
 * threshold as {@code FAILED} with {@code errorCode=stuck_run_reaped},
 * and releases CLAIMED refresh signals associated with the dead run
 * back to {@code PENDING} so the consumer's next tick can re-dispatch
 * them onto a fresh run.
 *
 * <p>Gated behind {@code breakcompliance.scheduling.enabled} (default
 * true) — the same toggle as {@link me.apet97.breakcompliance.addon.webhook.RefreshSignalConsumer}.
 */
@Component
@ConditionalOnProperty(
        name = "breakcompliance.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IngestionRunReaper {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunReaper.class);

    private final IngestionRunRepository runs;
    private final RefreshSignalRepository signals;
    private final TransactionTemplate tx;
    private final Duration stuckThreshold;

    public IngestionRunReaper(
            IngestionRunRepository runs,
            RefreshSignalRepository signals,
            PlatformTransactionManager txManager,
            @Value("${breakcompliance.ingest.stuck-threshold-ms:600000}") long stuckThresholdMs) {
        this.runs = runs;
        this.signals = signals;
        this.tx = new TransactionTemplate(txManager);
        this.stuckThreshold = Duration.ofMillis(stuckThresholdMs);
    }

    @Scheduled(fixedDelayString = "${breakcompliance.ingest.reaper-interval-ms:120000}")
    public void reapStuckRuns() {
        Instant cutoff = Instant.now().minus(stuckThreshold);
        List<IngestionRun> stuck = tx.execute(status -> runs
                .findByStatusAndCreatedAtBefore(IngestionStatus.RUNNING, cutoff));
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        for (IngestionRun run : stuck) {
            try {
                reapOne(run);
            } catch (RuntimeException e) {
                log.warn(
                        "ingest.reaper.failed workspace={} runId={} reason={}",
                        run.getWorkspaceId(),
                        run.getId(),
                        e.getClass().getSimpleName(),
                        e);
            }
        }
    }

    private void reapOne(IngestionRun run) {
        tx.executeWithoutResult(status -> {
            run.setStatus(IngestionStatus.FAILED);
            run.setErrorCode("stuck_run_reaped");
            run.setCompletedAt(Instant.now());
            runs.save(run);
            int released = signals.releaseClaimedSignalsForRun(run.getWorkspaceId(), run.getId());
            log.warn(
                    "ingest.reaper.reaped workspace={} runId={} createdAt={} releasedSignals={}",
                    run.getWorkspaceId(),
                    run.getId(),
                    run.getCreatedAt(),
                    released);
        });
    }
}
