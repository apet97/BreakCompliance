package me.apet97.breakcompliance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.apet97.breakcompliance.domain.BreakRuleEngine;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.FindingCode;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.TimezoneStrategy;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.entities.WorkspaceTimeOff;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceTimeOffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FindingsServiceTest {

    private static final String WORKSPACE_ID = "ws-findings";
    private static final String USER_ID = "user-a";

    @Mock
    WorkspaceSettingsRepository settingsRepo;
    @Mock
    TimeEntryRepository entriesRepo;
    @Mock
    FindingRepository findingsRepo;
    @Mock
    WorkspaceHolidayRepository holidayRepo;
    @Mock
    WorkspaceTimeOffRepository timeOffRepo;

    private FindingsService service;

    @BeforeEach
    void setUp() {
        service = new FindingsService(
                settingsRepo,
                entriesRepo,
                findingsRepo,
                new BreakRuleEngine(),
                holidayRepo,
                timeOffRepo);
        when(settingsRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(settings()));
        when(holidayRepo.findByWorkspaceIdAndDateBetween(eq(WORKSPACE_ID), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void partialDayTimeOff_doesNotSuppressWorkOutsideWindow() {
        LocalDate date = LocalDate.parse("2026-05-10");
        TimeEntry work = workEntry("work-1", "2026-05-10T09:00:00Z", "2026-05-10T14:00:00Z");
        WorkspaceTimeOff partialPto = timeOff("pto-1", "2026-05-10T15:00:00Z", "2026-05-10T17:00:00Z");
        when(entriesRepo.findByWorkspaceIdAndStartAtBetween(
                        WORKSPACE_ID,
                        Instant.parse("2026-05-10T00:00:00Z"),
                        Instant.parse("2026-05-11T00:00:00Z")))
                .thenReturn(List.of(work));
        when(timeOffRepo.findByWorkspaceIdAndStartAtLessThanAndEndAtGreaterThanEqual(
                        WORKSPACE_ID,
                        Instant.parse("2026-05-11T00:00:00Z"),
                        Instant.parse("2026-05-10T00:00:00Z")))
                .thenReturn(List.of(partialPto));
        when(findingsRepo.save(any(Finding.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Finding> findings = service.evaluateAndReplace(WORKSPACE_ID, date, date);

        assertThat(findings).extracting(Finding::getUserId).containsOnly(USER_ID);
        assertThat(findings).extracting(Finding::getCode)
                .contains(FindingCode.MISSING_REQUIRED_BREAK);
        verify(entriesRepo).findByWorkspaceIdAndStartAtBetween(
                WORKSPACE_ID,
                Instant.parse("2026-05-10T00:00:00Z"),
                Instant.parse("2026-05-11T00:00:00Z"));
    }

    @Test
    void intervalOnlyTimeOff_producesNoFindingsAndIsNotPersistedAsTimeEntry() {
        LocalDate date = LocalDate.parse("2026-05-10");
        WorkspaceTimeOff fullDayPto = timeOff("pto-1", "2026-05-10T00:00:00Z", "2026-05-11T00:00:00Z");
        when(entriesRepo.findByWorkspaceIdAndStartAtBetween(
                        WORKSPACE_ID,
                        Instant.parse("2026-05-10T00:00:00Z"),
                        Instant.parse("2026-05-11T00:00:00Z")))
                .thenReturn(List.of());
        when(timeOffRepo.findByWorkspaceIdAndStartAtLessThanAndEndAtGreaterThanEqual(
                        WORKSPACE_ID,
                        Instant.parse("2026-05-11T00:00:00Z"),
                        Instant.parse("2026-05-10T00:00:00Z")))
                .thenReturn(List.of(fullDayPto));

        List<Finding> findings = service.evaluateAndReplace(WORKSPACE_ID, date, date);

        assertThat(findings).isEmpty();
        verify(entriesRepo).findByWorkspaceIdAndStartAtBetween(
                WORKSPACE_ID,
                Instant.parse("2026-05-10T00:00:00Z"),
                Instant.parse("2026-05-11T00:00:00Z"));
    }

    private static WorkspaceSettings settings() {
        WorkspaceSettings s = new WorkspaceSettings();
        s.setWorkspaceId(WORKSPACE_ID);
        s.setAppliedPresetKey("custom-basic");
        s.setTimezoneStrategy(TimezoneStrategy.ENTRY_TIMEZONE);
        s.setFallbackDetectionEnabled(false);
        s.setCustomWorkThresholdMinutes(240);
        s.setCustomBreakThresholdMinutes(15);
        s.setCustomMinBreakSegmentMinutes(5);
        s.setCustomMaxContinuousWorkMinutes(600);
        s.setCustomGracePeriodMinutes(0);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return s;
    }

    private static TimeEntry workEntry(String sourceId, String start, String end) {
        Instant startAt = Instant.parse(start);
        Instant endAt = Instant.parse(end);
        TimeEntry e = new TimeEntry();
        e.setWorkspaceId(WORKSPACE_ID);
        e.setSourceEntryId(sourceId);
        e.setUserId(USER_ID);
        e.setUserName("User A");
        e.setStartAt(startAt);
        e.setEndAt(endAt);
        e.setDurationSeconds(Duration.between(startAt, endAt).toSeconds());
        e.setBillable(false);
        e.setTags(List.of());
        e.setRaw(Map.of("type", "REGULAR"));
        e.setIngestedAt(Instant.now());
        return e;
    }

    private static WorkspaceTimeOff timeOff(String sourceId, String start, String end) {
        WorkspaceTimeOff row = new WorkspaceTimeOff();
        row.setWorkspaceId(WORKSPACE_ID);
        row.setSourceId(sourceId);
        row.setUserId(USER_ID);
        row.setStartAt(Instant.parse(start));
        row.setEndAt(Instant.parse(end));
        row.setStatus("APPROVED");
        row.setIngestedAt(Instant.now());
        return row;
    }
}
