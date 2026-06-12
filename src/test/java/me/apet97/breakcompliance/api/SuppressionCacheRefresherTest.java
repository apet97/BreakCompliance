package me.apet97.breakcompliance.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.apet97.breakcompliance.clockify.HolidayFetcher;
import me.apet97.breakcompliance.clockify.TimeOffFetcher;
import me.apet97.breakcompliance.clockify.UserDirectoryFetcher;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceTimeOffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SuppressionCacheRefresherTest {

    @Test
    void refresh_attemptsTimeOffAndUserDirectoryWhenHolidayRefreshFails() {
        InstallationRepository installationRepo = mock(InstallationRepository.class);
        HolidayFetcher holidayFetcher = mock(HolidayFetcher.class);
        TimeOffFetcher timeOffFetcher = mock(TimeOffFetcher.class);
        UserDirectoryFetcher userDirectoryFetcher = mock(UserDirectoryFetcher.class);
        SuppressionCacheRefresher refresher = new SuppressionCacheRefresher(
                installationRepo,
                holidayFetcher,
                timeOffFetcher,
                mock(WorkspaceHolidayRepository.class),
                mock(WorkspaceTimeOffRepository.class),
                userDirectoryFetcher,
                mock(TimeEntryRepository.class),
                new NoopTxManager());

        Installation installation = new Installation();
        installation.setWorkspaceId("ws-test");
        installation.setBackendUrl("https://api.clockify.me/api");
        when(installationRepo.findByWorkspaceId("ws-test")).thenReturn(Optional.of(installation));
        when(holidayFetcher.fetch(eq("ws-test"), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("holidays down"));
        when(timeOffFetcher.fetchApproved(eq("ws-test"), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(userDirectoryFetcher.fetchActive("ws-test", "https://api.clockify.me/api", "addon-token"))
                .thenReturn(Map.of());

        LocalDate from = LocalDate.parse("2026-05-01");
        LocalDate to = LocalDate.parse("2026-05-07");
        assertThatCode(() -> refresher.refresh("ws-test", "addon-token", from, to))
                .doesNotThrowAnyException();

        verify(timeOffFetcher).fetchApproved("ws-test", "https://api.clockify.me/api", "addon-token", from, to);
        verify(userDirectoryFetcher).fetchActive("ws-test", "https://api.clockify.me/api", "addon-token");
    }

    private static class NoopTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
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
