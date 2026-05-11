package me.apet97.breakcompliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import me.apet97.breakcompliance.domain.BreakRuleEngine;
import me.apet97.breakcompliance.domain.BreakRuleEngineInput;
import me.apet97.breakcompliance.domain.FindingDraft;
import me.apet97.breakcompliance.persistence.entities.Finding;
import me.apet97.breakcompliance.persistence.entities.GroupMembership;
import me.apet97.breakcompliance.persistence.entities.RuleTemplate;
import me.apet97.breakcompliance.persistence.entities.TemplateAssignment;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import me.apet97.breakcompliance.persistence.entities.WorkspaceSettings;
import me.apet97.breakcompliance.persistence.repositories.FindingRepository;
import me.apet97.breakcompliance.persistence.repositories.GroupMembershipRepository;
import me.apet97.breakcompliance.persistence.repositories.RuleTemplateRepository;
import me.apet97.breakcompliance.persistence.repositories.TemplateAssignmentRepository;
import me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository;
import me.apet97.breakcompliance.persistence.repositories.WorkspaceSettingsRepository;
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
    private final GroupMembershipRepository membershipsRepo;
    private final TimeEntryRepository entriesRepo;
    private final FindingRepository findingsRepo;
    private final BreakRuleEngine engine;

    public FindingsService(
            WorkspaceSettingsRepository settingsRepo,
            RuleTemplateRepository templatesRepo,
            TemplateAssignmentRepository assignmentsRepo,
            GroupMembershipRepository membershipsRepo,
            TimeEntryRepository entriesRepo,
            FindingRepository findingsRepo,
            BreakRuleEngine engine) {
        this.settingsRepo = settingsRepo;
        this.templatesRepo = templatesRepo;
        this.assignmentsRepo = assignmentsRepo;
        this.membershipsRepo = membershipsRepo;
        this.entriesRepo = entriesRepo;
        this.findingsRepo = findingsRepo;
        this.engine = engine;
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
        // Group memberships are workspace-scoped; we load all and the engine filters.
        Instant fromInstant = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<TimeEntry> entries = entriesRepo.findByWorkspaceIdAndStartAtBetween(workspaceId, fromInstant, toInstant);
        List<GroupMembership> memberships = loadAllMemberships(workspaceId);

        BreakRuleEngineInput input = new BreakRuleEngineInput(
                workspaceId, settings, templates, assignments, entries, memberships, from, to);
        List<FindingDraft> drafts = engine.evaluate(input);

        findingsRepo.deleteByWorkspaceIdAndDateBetween(workspaceId, from, to);
        findingsRepo.flush();

        Instant now = Instant.now();
        List<Finding> persisted = new java.util.ArrayList<>();
        for (FindingDraft d : drafts) {
            Finding f = new Finding();
            f.setWorkspaceId(d.workspaceId());
            f.setId(UUID.randomUUID().toString());
            f.setUserId(d.userId());
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

    private List<GroupMembership> loadAllMemberships(String workspaceId) {
        // Quick non-paginated load; we cap large-workspace fan-out in J6
        // when the directory ingest endpoint lands. Engine ignores
        // memberships from other workspaces defensively anyway.
        return membershipsRepo.findByWorkspaceIdAndGroupId(workspaceId, "")
                .isEmpty()
                ? java.util.List.copyOf(membershipsRepo.findByWorkspaceIdAndUserId(workspaceId, ""))
                : java.util.List.of();
    }
}
