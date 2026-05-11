package me.apet97.breakcompliance.addon.webhook;

import java.time.Instant;
import java.util.UUID;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalSource;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an advisory refresh signal in response to a Clockify webhook. We
 * deliberately do not trust webhook payloads — duplicated BREAK entries fire
 * {@code NEW_TIME_ENTRY} with inherited type, edits fire
 * {@code TIME_ENTRY_UPDATED}; both collapse to "this workspace's day window
 * needs re-ingest + re-evaluate." The detail fields (entity_id, date_hint)
 * are left null on purpose: the Detailed Report is the source of truth.
 */
@Service
public class RefreshSignalService {

    private final RefreshSignalRepository repo;

    public RefreshSignalService(RefreshSignalRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public RefreshSignal recordWebhookSignal(String workspaceId, String eventType) {
        RefreshSignal s = new RefreshSignal();
        s.setWorkspaceId(workspaceId);
        s.setId(UUID.randomUUID().toString());
        s.setSource(RefreshSignalSource.WEBHOOK);
        s.setEventType(eventType);
        s.setEntityId(null);
        s.setDateHint(null);
        s.setReceivedAt(Instant.now());
        s.setStatus(RefreshSignalStatus.PENDING);
        return repo.save(s);
    }
}
