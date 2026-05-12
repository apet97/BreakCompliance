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
 * deliberately do not trust webhook payloads for accounting — duplicated
 * BREAK entries fire {@code NEW_TIME_ENTRY} with inherited type, edits
 * fire {@code TIME_ENTRY_UPDATED}; both collapse to "this workspace's day
 * window needs re-ingest + re-evaluate." The detailed report stays the
 * source of truth for actual entry contents.
 *
 * <p>The {@code dateHint} is captured when the webhook payload carries an
 * entry start timestamp, so the refresh-signal consumer (Phase 2) can
 * compute the smallest covering re-ingest window instead of always
 * burning a default 7-day fetch. A null hint just means "no date info,
 * fall back to default window" — handler still records the signal.
 */
@Service
public class RefreshSignalService {

    private final RefreshSignalRepository repo;

    public RefreshSignalService(RefreshSignalRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public RefreshSignal recordWebhookSignal(String workspaceId, String eventType) {
        return recordWebhookSignal(workspaceId, eventType, null);
    }

    @Transactional
    public RefreshSignal recordWebhookSignal(String workspaceId, String eventType, String dateHint) {
        RefreshSignal s = new RefreshSignal();
        s.setWorkspaceId(workspaceId);
        s.setId(UUID.randomUUID().toString());
        s.setSource(RefreshSignalSource.WEBHOOK);
        s.setEventType(eventType);
        s.setEntityId(null);
        s.setDateHint(dateHint);
        s.setReceivedAt(Instant.now());
        s.setStatus(RefreshSignalStatus.PENDING);
        return repo.save(s);
    }
}
