package me.apet97.breakcompliance.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import me.apet97.breakcompliance.addon.auth.TestClockifyKeyConfig;
import me.apet97.breakcompliance.persistence.PostgresTestcontainersConfig;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalSource;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Locks the reaper's recovery contract: a {@code RUNNING} run older
 * than the stuck threshold transitions to {@code FAILED} with the
 * documented error code, and any refresh signals CLAIMED for that run
 * get released back to {@code PENDING} so the consumer's next poll
 * can retry them on a fresh run.
 *
 * <p>{@code breakcompliance.scheduling.enabled=true} is overridden so
 * the bean is created; the reaper's {@code @Scheduled} method is
 * driven directly from the test to avoid timing flake (the default
 * interval is 2 minutes — too long for an isolated fixture).
 */
@SpringBootTest
@Import({PostgresTestcontainersConfig.class, TestClockifyKeyConfig.class})
@TestPropertySource(properties = {
        "breakcompliance.scheduling.enabled=true",
        "breakcompliance.ingest.stuck-threshold-ms=1000",
        "breakcompliance.ingest.reaper-interval-ms=600000"
})
class IngestionRunReaperTest {

    private static final String WORKSPACE = "ws-reaper";

    @Autowired
    IngestionRunReaper reaper;

    @Autowired
    IngestionRunRepository runs;

    @Autowired
    RefreshSignalRepository signals;

    @BeforeEach
    void clean() {
        signals.deleteAll();
        runs.deleteAll();
    }

    @Test
    void reapStuckRuns_oldRunningRun_flippedToFailedAndSignalsReleased() {
        // 30-minute-old RUNNING run + 2 signals CLAIMED for that run.
        IngestionRun stuck = seedRun(IngestionStatus.RUNNING, Instant.now().minusSeconds(1800));
        seedSignal("s1", stuck.getId(), RefreshSignalStatus.CLAIMED);
        seedSignal("s2", stuck.getId(), RefreshSignalStatus.CLAIMED);
        // Plus a recent RUNNING that should NOT be reaped.
        IngestionRun fresh = seedRun(IngestionStatus.RUNNING, Instant.now());

        reaper.reapStuckRuns();

        IngestionRun reaped = runs.findById(new IngestionRun.Pk(WORKSPACE, stuck.getId())).orElseThrow();
        assertThat(reaped.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(reaped.getErrorCode()).isEqualTo("stuck_run_reaped");

        IngestionRun freshAfter = runs.findById(new IngestionRun.Pk(WORKSPACE, fresh.getId())).orElseThrow();
        assertThat(freshAfter.getStatus()).isEqualTo(IngestionStatus.RUNNING);

        // Signals released back to PENDING with runId cleared.
        for (RefreshSignal s : signals.findByWorkspaceIdOrderByReceivedAtDesc(WORKSPACE)) {
            assertThat(s.getStatus()).isEqualTo(RefreshSignalStatus.PENDING);
            assertThat(s.getIngestionRunId()).isNull();
        }
    }

    @Test
    void reapStuckRuns_noStuckRuns_noOp() {
        seedRun(IngestionStatus.COMPLETED, Instant.now().minusSeconds(3600));
        seedRun(IngestionStatus.FAILED, Instant.now().minusSeconds(3600));
        seedRun(IngestionStatus.RUNNING, Instant.now()); // too fresh

        reaper.reapStuckRuns();

        long stillRunning = runs.findByWorkspaceIdOrderByCreatedAtDesc(WORKSPACE).stream()
                .filter(r -> r.getStatus() == IngestionStatus.RUNNING).count();
        assertThat(stillRunning).isEqualTo(1);
    }

    private IngestionRun seedRun(IngestionStatus status, Instant createdAt) {
        IngestionRun run = new IngestionRun();
        run.setWorkspaceId(WORKSPACE);
        run.setId(UUID.randomUUID().toString());
        run.setDateRangeStart("2026-05-01");
        run.setDateRangeEnd("2026-05-07");
        run.setStatus(status);
        run.setEntriesProcessed(0);
        run.setCreatedAt(createdAt);
        run.setCompletedAt(createdAt);
        return runs.saveAndFlush(run);
    }

    private void seedSignal(String suffix, String runId, RefreshSignalStatus status) {
        RefreshSignal s = new RefreshSignal();
        s.setWorkspaceId(WORKSPACE);
        s.setId(UUID.randomUUID().toString() + "-" + suffix);
        s.setSource(RefreshSignalSource.WEBHOOK);
        s.setEventType("NEW_TIME_ENTRY");
        s.setReceivedAt(Instant.now().minusSeconds(60));
        s.setStatus(status);
        s.setIngestionRunId(runId);
        signals.saveAndFlush(s);
    }
}
