package me.apet97.breakcompliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.apet97.breakcompliance.domain.BreakRuleEngine;
import me.apet97.breakcompliance.domain.BreakRuleEngineInput;
import me.apet97.breakcompliance.domain.FindingDraft;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import me.apet97.breakcompliance.persistence.entities.WorkspaceTimeOff;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceHolidayRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceTimeOffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a re-evaluation of break-rule findings for a (workspaceId,
 * date range): loads inputs from JPA, runs {@link BreakRuleEngine}, and
 * atomically replaces existing findings in the range with the newly produced
 * set. Atomic replace prevents an admin from seeing a half-evaluated state
 * while the engine is mid-flight on a large window.
 */
@Service
public class FindingsService {

    private final WorkspaceSettingsRepository settingsRepo;
    private final TimeEntryRepository entriesRepo;
    private final FindingRepository findingsRepo;
    private final BreakRuleEngine engine;
    private final WorkspaceHolidayRepository holidayRepo;
    private final WorkspaceTimeOffRepository timeOffRepo;

    public FindingsService(
            WorkspaceSettingsRepository settingsRepo,
            TimeEntryRepository entriesRepo,
            FindingRepository findingsRepo,
            BreakRuleEngine engine,
            WorkspaceHolidayRepository holidayRepo,
            WorkspaceTimeOffRepository timeOffRepo) {
        this.settingsRepo = settingsRepo;
        this.entriesRepo = entriesRepo;
        this.findingsRepo = findingsRepo;
        this.engine = engine;
        this.holidayRepo = holidayRepo;
        this.timeOffRepo = timeOffRepo;
    }

    @Transactional
    public List<Finding> evaluateAndReplace(String workspaceId, LocalDate from, LocalDate to) {
        WorkspaceSettings settings = settingsRepo.findById(workspaceId).orElseGet(() -> {
            WorkspaceSettings s = new WorkspaceSettings();
            s.setWorkspaceId(workspaceId);
            return s;
        });
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<TimeEntry> entries = entriesRepo.findByWorkspaceIdAndStartAtBetween(workspaceId, fromInstant, toInstant);

        // P1.1 / P1.2 — pull cached suppression data. Holidays remain
        // date-level suppressions; approved time off is converted into
        // evaluation-only TIME_OFF entries so partial-day requests keep
        // their interval precision and real same-day WORK still evaluates.
        Set<LocalDate> workspaceWide = new HashSet<>();
        Map<String, Set<LocalDate>> perUser = new HashMap<>();
        for (WorkspaceHoliday h : holidayRepo.findByWorkspaceIdAndDateBetween(workspaceId, from, to)) {
            if (h.getAppliesToUserId() == null) {
                workspaceWide.add(h.getDate());
            } else {
                perUser.computeIfAbsent(h.getAppliesToUserId(), k -> new HashSet<>())
                        .add(h.getDate());
            }
        }
        List<TimeEntry> evaluationEntries = new ArrayList<>(entries);
        for (WorkspaceTimeOff t : timeOffRepo
                .findByWorkspaceIdAndStartAtLessThanAndEndAtGreaterThanEqual(
                        workspaceId, toInstant, fromInstant)) {
            evaluationEntries.addAll(syntheticTimeOffEntries(workspaceId, t, from, to));
        }

        BreakRuleEngineInput input = new BreakRuleEngineInput(
                workspaceId, settings, evaluationEntries, from, to, workspaceWide, perUser);
        List<FindingDraft> drafts = engine.evaluate(input);

        // Build a userId → userName lookup from the time entries we just
        // pulled (the engine doesn't carry names; we attach them here so
        // findings render human-readable in the sidebar). Last-write-wins
        // when an entry's name is blank vs. set; sticky to non-blank.
        Map<String, String> userNameByUserId = new HashMap<>();
        for (TimeEntry e : entries) {
            if (e.getUserId() == null || e.getUserName() == null || e.getUserName().isBlank()) continue;
            userNameByUserId.putIfAbsent(e.getUserId(), e.getUserName());
        }

        findingsRepo.deleteByWorkspaceIdAndDateBetween(workspaceId, from, to);
        findingsRepo.flush();

        Instant now = Instant.now();
        List<Finding> persisted = new java.util.ArrayList<>();
        for (FindingDraft d : drafts) {
            Finding f = new Finding();
            f.setWorkspaceId(d.workspaceId());
            f.setId(UUID.randomUUID().toString());
            f.setUserId(d.userId());
            f.setUserName(userNameByUserId.get(d.userId()));
            f.setDate(d.date());
            f.setTemplateId(d.templateId());
            f.setSeverity(d.severity());
            f.setCode(d.code());
            f.setMessage(d.message());
            f.setEvidence(d.evidence());
            f.setCreatedAt(now);
            persisted.add(findingsRepo.save(f));
        }
        return persisted;
    }

    public List<Finding> list(String workspaceId, LocalDate from, LocalDate to) {
        return findingsRepo.findByWorkspaceIdAndDateBetween(workspaceId, from, to);
    }

    /**
     * Existence check scoped to the JWT workspace. Used by the review
     * endpoint so an admin can't review another workspace's finding by
     * guessing its id — the workspace_id half of the composite PK is the
     * tenant boundary.
     */
    public boolean exists(String workspaceId, String findingId) {
        return findingsRepo.existsById(new me.apet97.breakcompliance.persistence.entities.Finding.Pk(workspaceId, findingId));
    }

    private static List<TimeEntry> syntheticTimeOffEntries(
            String workspaceId, WorkspaceTimeOff row, LocalDate from, LocalDate to) {
        if (row.getStartAt() == null || row.getEndAt() == null || !row.getEndAt().isAfter(row.getStartAt())) {
            return List.of();
        }
        Instant rangeStart = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant clippedStart = row.getStartAt().isAfter(rangeStart) ? row.getStartAt() : rangeStart;
        Instant clippedEnd = row.getEndAt().isBefore(rangeEnd) ? row.getEndAt() : rangeEnd;
        if (!clippedEnd.isAfter(clippedStart)) {
            return List.of();
        }

        List<TimeEntry> synthetic = new ArrayList<>();
        LocalDate day = clippedStart.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate lastDay = clippedEnd.minus(1, ChronoUnit.NANOS).atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate d = day; !d.isAfter(lastDay); d = d.plusDays(1)) {
            Instant dayStart = d.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant start = clippedStart.isAfter(dayStart) ? clippedStart : dayStart;
            Instant end = clippedEnd.isBefore(dayEnd) ? clippedEnd : dayEnd;
            if (!end.isAfter(start)) {
                continue;
            }
            TimeEntry e = new TimeEntry();
            e.setWorkspaceId(workspaceId);
            e.setSourceEntryId("timeoff:" + row.getSourceId() + ":" + d);
            e.setUserId(row.getUserId());
            e.setStartAt(start);
            e.setEndAt(end);
            e.setDurationSeconds(java.time.Duration.between(start, end).toSeconds());
            e.setBillable(false);
            e.setTags(List.of());
            e.setRaw(Map.of("type", "TIME_OFF", "synthetic", true));
            e.setIngestedAt(row.getIngestedAt() != null ? row.getIngestedAt() : Instant.now());
            synthetic.add(e);
        }
        return synthetic;
    }
}
