package me.apet97.breakcompliance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.apet97.breakcompliance.clockify.DetailedReportFetcher;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class IngestionServiceTest {

    @Test
    void beginAsync_whenUniqueRunningRunIndexWinsRace_returnsExistingRunId() {
        InstallationRepository installationRepo = mock(InstallationRepository.class);
        IngestionRunRepository runRepo = mock(IngestionRunRepository.class);
        TokenCodec codec = mock(TokenCodec.class);
        RecordingTxManager txManager = new RecordingTxManager();
        IngestionService service = new IngestionService(
                installationRepo,
                runRepo,
                mock(DetailedReportFetcher.class),
                codec,
                txManager,
                Runnable::run,
                new SimpleMeterRegistry(),
                mock(WorkspaceSettingsRepository.class),
                mock(TimeEntryUpserter.class),
                mock(SuppressionCacheRefresher.class));

        Installation installation = new Installation();
        installation.setWorkspaceId("ws-test");
        installation.setStatus(InstallationStatus.ACTIVE);
        installation.setReportsUrl("https://reports.api.clockify.me");
        installation.setAuthToken(new EncryptedToken("default", new byte[] {1, 2, 3}));
        when(installationRepo.findByWorkspaceId("ws-test")).thenReturn(Optional.of(installation));
        when(codec.decrypt("default", new byte[] {1, 2, 3})).thenReturn("addon-token");
        when(runRepo.findFirstByWorkspaceIdAndStatusAndDateRangeStartAndDateRangeEnd(
                "ws-test", IngestionStatus.RUNNING, "2026-05-01", "2026-05-07"))
                .thenReturn(Optional.empty(), Optional.of(existingRun("existing-run-1")));
        when(runRepo.saveAndFlush(any(IngestionRun.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate running run"));

        assertThatThrownBy(() -> service.beginAsync(
                "ws-test",
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-07"),
                null))
                .isInstanceOf(IngestionRunInProgressException.class)
                .extracting("existingRunId")
                .isEqualTo("existing-run-1");

        verify(runRepo).saveAndFlush(any(IngestionRun.class));
        assertThat(txManager.propagations())
                .containsExactly(
                        TransactionDefinition.PROPAGATION_REQUIRED,
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void executeRun_keepsRunRunningUntilSuppressionRefreshReturns() {
        IngestionRun run = runningRun("run-1");
        DetailedReportFetcher fetcher = mock(DetailedReportFetcher.class);
        SuppressionCacheRefresher suppression = mock(SuppressionCacheRefresher.class);
        IngestionRunRepository runRepo = mock(IngestionRunRepository.class);
        TimeEntryUpserter upserter = mock(TimeEntryUpserter.class);
        IngestionService service = serviceForExecuteRun(fetcher, runRepo, upserter, suppression);

        when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(List.of());
        when(runRepo.findById(new IngestionRun.Pk("ws-test", "run-1")))
                .thenReturn(Optional.of(run));
        when(runRepo.saveAndFlush(any(IngestionRun.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            assertThat(run.getStatus()).isEqualTo(IngestionStatus.RUNNING);
            assertThat(run.getEntriesProcessed()).isZero();
            return null;
        }).when(suppression).refresh(
                "ws-test",
                "addon-token",
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-07"));

        service.executeRun(
                "ws-test",
                "run-1",
                "addon-token",
                "https://reports.api.clockify.me",
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-07"));

        assertThat(run.getStatus()).isEqualTo(IngestionStatus.COMPLETED);
    }

    @Test
    void executeRun_marksCompletedAfterSuppressionRefreshFailure() {
        IngestionRun run = runningRun("run-1");
        DetailedReportFetcher fetcher = mock(DetailedReportFetcher.class);
        SuppressionCacheRefresher suppression = mock(SuppressionCacheRefresher.class);
        IngestionRunRepository runRepo = mock(IngestionRunRepository.class);
        TimeEntryUpserter upserter = mock(TimeEntryUpserter.class);
        IngestionService service = serviceForExecuteRun(fetcher, runRepo, upserter, suppression);

        when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(List.of());
        when(runRepo.findById(new IngestionRun.Pk("ws-test", "run-1")))
                .thenReturn(Optional.of(run));
        when(runRepo.saveAndFlush(any(IngestionRun.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            assertThat(run.getStatus()).isEqualTo(IngestionStatus.RUNNING);
            throw new RuntimeException("suppression unavailable");
        }).when(suppression).refresh(
                "ws-test",
                "addon-token",
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-07"));

        service.executeRun(
                "ws-test",
                "run-1",
                "addon-token",
                "https://reports.api.clockify.me",
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-07"));

        assertThat(run.getStatus()).isEqualTo(IngestionStatus.COMPLETED);
    }

    private static IngestionService serviceForExecuteRun(
            DetailedReportFetcher fetcher,
            IngestionRunRepository runRepo,
            TimeEntryUpserter upserter,
            SuppressionCacheRefresher suppression) {
        InstallationRepository installationRepo = mock(InstallationRepository.class);
        TokenCodec codec = mock(TokenCodec.class);
        return new IngestionService(
                installationRepo,
                runRepo,
                fetcher,
                codec,
                new RecordingTxManager(),
                Runnable::run,
                new SimpleMeterRegistry(),
                mock(WorkspaceSettingsRepository.class),
                upserter,
                suppression);
    }

    private static IngestionRun runningRun(String id) {
        IngestionRun run = new IngestionRun();
        run.setWorkspaceId("ws-test");
        run.setId(id);
        run.setDateRangeStart("2026-05-01");
        run.setDateRangeEnd("2026-05-07");
        run.setStatus(IngestionStatus.RUNNING);
        run.setEntriesProcessed(0);
        Instant now = Instant.now();
        run.setCreatedAt(now);
        run.setCompletedAt(now);
        return run;
    }

    private static IngestionRun existingRun(String id) {
        IngestionRun run = new IngestionRun();
        run.setWorkspaceId("ws-test");
        run.setId(id);
        run.setDateRangeStart("2026-05-01");
        run.setDateRangeEnd("2026-05-07");
        run.setStatus(IngestionStatus.RUNNING);
        run.setCreatedAt(Instant.now());
        run.setCompletedAt(Instant.now());
        return run;
    }

    private static class RecordingTxManager implements PlatformTransactionManager {
        private final List<Integer> propagations = new ArrayList<>();

        List<Integer> propagations() {
            return propagations;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
