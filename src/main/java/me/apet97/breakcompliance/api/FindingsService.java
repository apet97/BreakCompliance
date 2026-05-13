package me.apet97.breakcompliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import me.apet97.breakcompliance.domain.BreakRuleEngine;
import me.apet97.breakcompliance.domain.BreakRuleEngineInput;
import me.apet97.breakcompliance.domain.FindingDraft;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import me.apet97.breakcompliance.persistence.entities.WorkspaceTimeOff;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.TemplateAssignmentRepository;
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
    private final RuleTemplateRepository templatesRepo;
    private final TemplateAssignmentRepository assignmentsRepo;
    private final TimeEntryRepository entriesRepo;
    private final FindingRepository findingsRepo;
    private final BreakRuleEngine engine;
    private final WorkspaceHolidayRepository holidayRepo;
    private final WorkspaceTimeOffRepository timeOffRepo;

    public FindingsService(
            WorkspaceSettingsRepository settingsRepo,
            RuleTemplateRepository templatesRepo,
            TemplateAssignmentRepository assignmentsRepo,
            TimeEntryRepository entriesRepo,
            FindingRepository findingsRepo,
            BreakRuleEngine engine,
            WorkspaceHolidayRepository holidayRepo,
            WorkspaceTimeOffRepository timeOffRepo) {
        this.settingsRepo = settingsRepo;
        this.templatesRepo = templatesRepo;
        this.assignmentsRepo = assignmentsRepo;
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
        List<RuleTemplate> templates = templatesRepo.findByWorkspaceId(workspaceId);
        List<TemplateAssignment> assignments = assignmentsRepo.findByWorkspaceId(workspaceId);
        Instant fromInstant = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<TimeEntry> entries = entriesRepo.findByWorkspaceIdAndStartAtBetween(workspaceId, fromInstant, toInstant);

        // P1.1 / P1.2 — pull cached holidays + approved time-off and build
        // the suppression sets the engine consumes. Workspace-wide
        // holidays apply to every user's bucket for that date; per-user
        // holidays + every approved time-off window only apply to the
        // matching user.
        java.util.Set<LocalDate> workspaceWide = new java.util.HashSet<>();
        java.util.Map<String, java.util.Set<LocalDate>> perUser = new java.util.HashMap<>();
        for (WorkspaceHoliday h : holidayRepo.findByWorkspaceIdAndDateBetween(workspaceId, from, to)) {
            if (h.getAppliesToUserId() == null) {
                workspaceWide.add(h.getDate());
            } else {
                perUser.computeIfAbsent(h.getAppliesToUserId(), k -> new java.util.HashSet<>())
                        .add(h.getDate());
            }
        }
        for (WorkspaceTimeOff t : timeOffRepo
                .findByWorkspaceIdAndStartAtLessThanAndEndAtGreaterThanEqual(
                        workspaceId, toInstant, fromInstant)) {
            // Expand the (start, end) span into a set of LocalDates in UTC.
            // Engine bucketing already handles per-entry timezones; we use
            // UTC here as a safe baseline that matches Clockify's storage.
            LocalDate dStart = t.getStartAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            LocalDate dEnd = t.getEndAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            java.util.Set<LocalDate> dates = perUser.computeIfAbsent(
                    t.getUserId(), k -> new java.util.HashSet<>());
            for (LocalDate d = dStart; !d.isAfter(dEnd); d = d.plusDays(1)) {
                dates.add(d);
            }
        }

        // groupMemberships intentionally empty: the synthesised workspace
        // template is single per workspace and the engine does not consult
        // memberships. The record field stays for a future per-group policy.
        BreakRuleEngineInput input = new BreakRuleEngineInput(
                workspaceId, settings, templates, assignments, entries, java.util.List.of(),
                from, to, workspaceWide, perUser);
        List<FindingDraft> drafts = engine.evaluate(input);

        // Build a userId → userName lookup from the time entries we just
        // pulled (the engine doesn't carry names; we attach them here so
        // findings render human-readable in the sidebar). Last-write-wins
        // when an entry's name is blank vs. set; sticky to non-blank.
        java.util.Map<String, String> userNameByUserId = new java.util.HashMap<>();
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
}
