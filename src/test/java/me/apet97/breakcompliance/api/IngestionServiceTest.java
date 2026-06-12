package me.apet97.breakcompliance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import me.apet97.breakcompliance.clockify.HolidayFetcher;
import me.apet97.breakcompliance.clockify.TimeOffFetcher;
import me.apet97.breakcompliance.clockify.UserDirectoryFetcher;
import me.apet97.breakcompliance.persistence.crypto.EncryptedToken;
import me.apet97.breakcompliance.persistence.crypto.TokenCodec;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.InstallationStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceTimeOffRepository;
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
                mock(TimeEntryRepository.class),
                runRepo,
                mock(DetailedReportFetcher.class),
                codec,
                txManager,
                Runnable::run,
                new SimpleMeterRegistry(),
                mock(WorkspaceSettingsRepository.class),
                mock(HolidayFetcher.class),
                mock(TimeOffFetcher.class),
                mock(WorkspaceHolidayRepository.class),
                mock(WorkspaceTimeOffRepository.class),
                mock(UserDirectoryFetcher.class));

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
