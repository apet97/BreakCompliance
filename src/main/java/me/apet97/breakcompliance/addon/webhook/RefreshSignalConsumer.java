package me.apet97.breakcompliance.addon.webhook;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import me.apet97.breakcompliance.api.FindingsService;
import me.apet97.breakcompliance.api.IngestionRunInProgressException;
import me.apet97.breakcompliance.api.IngestionService;
import me.apet97.breakcompliance.api.InstallationInactiveException;
import me.apet97.breakcompliance.config.MetricsConfig;
import me.apet97.breakcompliance.persistence.entities.IngestionRun;
import me.apet97.breakcompliance.persistence.entities.IngestionStatus;
import me.apet97.breakcompliance.persistence.entities.Installation;
import me.apet97.breakcompliance.persistence.entities.RefreshSignal;
import me.apet97.breakcompliance.persistence.entities.RefreshSignalStatus;
import me.apet97.breakcompliance.persistence.repositories.IngestionRunRepository;
import me.apet97.breakcompliance.persistence.repositories.InstallationRepository;
import me.apet97.breakcompliance.persistence.repositories.RefreshSignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls refresh signals off the inbox and turns them into ingest runs.
 *
 * <p>Webhooks land in {@link RefreshSignal} rows with {@code PENDING}
 * status. After a configurable debounce window (so a burst of edits to
 * the same workspace coalesces into one re-ingest), this consumer:
 *
 * <ol>
 *   <li>Reads all {@code PENDING} signals older than the debounce
 *       threshold, grouped by workspace.</li>
 *   <li>Computes a {@code [from, to]} date window from each signal's
 *       {@code dateHint} (falling back to {@code last N days} when no
 *       hints are available), capped at {@code max-window-days}.</li>
 *   <li>If an in-flight {@link IngestionRun} already covers the same
 *       window, marks the signals {@code COALESCED} with a back-pointer
 *       to that run.</li>
 *   <li>Otherwise dispatches a fresh async ingest via
 *       {@link IngestionService#beginAsyncForRefresh} with a post-finalize
 *       callback that re-evaluates findings and transitions the signals
 *       to {@code CONSUMED}.</li>
 * </ol>
 *
 * <p>A signal that stays {@code CLAIMED} forever indicates the run
 * crashed mid-flight (executor died, JVM restart). The stuck-run reaper
 * planned for Phase 3.1 will reset such signals back to {@code PENDING}.
 *
 * <p>The consumer is registered only when
 * {@code breakcompliance.scheduling.enabled=true} (default true). Tests
 * disable it via {@code application-test.yaml} or
 * {@code @TestPropertySource} so {@code @Scheduled} doesn't fire during
 * isolated transactional fixtures.
 */
@Component
@ConditionalOnProperty(
        name = "breakcompliance.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RefreshSignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefreshSignalConsumer.class);

    private final RefreshSignalRepository signals;
    private final IngestionRunRepository runs;
    private final InstallationRepository installations;
    private final IngestionService ingestion;
    private final FindingsService findings;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    private final Duration debounce;
    private final int fallbackWindowDays;
    private final int maxWindowDays;

    public RefreshSignalConsumer(
            RefreshSignalRepository signals,
            IngestionRunRepository runs,
            InstallationRepository installations,
            IngestionService ingestion,
            FindingsService findings,
            PlatformTransactionManager txManager,
            MeterRegistry meters,
            @Value("${breakcompliance.refresh.debounce-ms:20000}") long debounceMs,
            @Value("${breakcompliance.refresh.fallback-window-days:7}") int fallbackWindowDays,
            @Value("${breakcompliance.refresh.max-window-days:30}") int maxWindowDays) {
        this.signals = signals;
        this.runs = runs;
        this.installations = installations;
        this.ingestion = ingestion;
        this.findings = findings;
        this.tx = new TransactionTemplate(txManager);
        this.meters = meters;
        this.debounce = Duration.ofMillis(debounceMs);
        this.fallbackWindowDays = fallbackWindowDays;
        this.maxWindowDays = maxWindowDays;
    }

    @Scheduled(fixedDelayString = "${breakcompliance.refresh.poll-interval-ms:30000}")
    public void pollAndDispatch() {
        Instant cutoff = Instant.now().minus(debounce);
        List<RefreshSignal> pending = tx.execute(status -> signals
                .findByStatusAndReceivedAtBeforeOrderByWorkspaceIdAscReceivedAtAsc(
                        RefreshSignalStatus.PENDING, cutoff));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Map<String, List<RefreshSignal>> byWorkspace = pending.stream()
                .collect(Collectors.groupingBy(
                        RefreshSignal::getWorkspaceId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, List<RefreshSignal>> entry : byWorkspace.entrySet()) {
            try {
                dispatchForWorkspace(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                log.warn(
                        "refresh.consumer.dispatch-failed workspace={} reason={}",
                        entry.getKey(),
                        e.getClass().getSimpleName(),
                        e);
            }
        }
    }

    private void dispatchForWorkspace(String workspaceId, List<RefreshSignal> group) {
        DateRange window = computeWindow(group);
        List<String> signalIds = group.stream().map(RefreshSignal::getId).toList();

        // Dedupe: coalesce onto any in-flight run covering the same window.
        Optional<IngestionRun> inFlight = tx.execute(status -> runs
                .findFirstByWorkspaceIdAndStatusAndDateRangeStartAndDateRangeEnd(
                        workspaceId,
                        IngestionStatus.RUNNING,
                        window.from().toString(),
                        window.to().toString()));
        if (inFlight != null && inFlight.isPresent()) {
            String runId = inFlight.get().getId();
            tx.executeWithoutResult(status -> signals.updateStatusAndRunId(
                    workspaceId, signalIds, RefreshSignalStatus.COALESCED, runId));
            meters.counter(MetricsConfig.REFRESH_SIGNALS_PROCESSED, "outcome", "coalesced")
                    .increment(signalIds.size());
            log.info(
                    "refresh.consumer.coalesced workspace={} runId={} signals={} window={}..{}",
                    workspaceId,
                    runId,
                    signalIds.size(),
                    window.from(),
                    window.to());
            return;
        }

        // Need a reportsUrl. The installation row carries the cached value
        // populated from the last user-token request. If we don't have one,
        // there's no way to ingest — fail the signals so the queue stays
        // clean and operators can investigate.
        Installation install = installations.findByWorkspaceId(workspaceId).orElse(null);
        if (install == null || install.getReportsUrl() == null || install.getReportsUrl().isBlank()) {
            tx.executeWithoutResult(status -> signals.updateStatus(
                    workspaceId, signalIds, RefreshSignalStatus.FAILED));
            meters.counter(MetricsConfig.REFRESH_SIGNALS_PROCESSED, "outcome", "no_installation")
                    .increment(signalIds.size());
            log.warn(
                    "refresh.consumer.no-reports-url workspace={} signals={}",
                    workspaceId,
                    signalIds.size());
            return;
        }

        IngestionRun run;
        try {
            // The callback receives the run id so it can back-reference
            // CLAIMED rows when transitioning to CONSUMED. In sync test
            // mode the callback runs inside beginAsyncForRefresh; the
            // PENDING → CONSUMED jump skips CLAIMED entirely. In async
            // production mode the callback fires later, after our
            // pre-CLAIM step below has landed.
            run = ingestion.beginAsyncForRefresh(
                    workspaceId,
                    window.from(),
                    window.to(),
                    install.getReportsUrl(),
                    runId -> {
                        findings.evaluateAndReplace(workspaceId, window.from(), window.to());
                        tx.executeWithoutResult(s -> signals.updateStatusAndRunId(
                                workspaceId, signalIds, RefreshSignalStatus.CONSUMED, runId));
                    });
        } catch (InstallationInactiveException e) {
            tx.executeWithoutResult(status -> signals.updateStatus(
                    workspaceId, signalIds, RefreshSignalStatus.FAILED));
            meters.counter(MetricsConfig.REFRESH_SIGNALS_PROCESSED, "outcome", "inactive")
                    .increment(signalIds.size());
            log.info(
                    "refresh.consumer.installation-inactive workspace={} signals={}",
                    workspaceId,
                    signalIds.size());
            return;
        } catch (IngestionRunInProgressException e) {
            // Race with concurrent consumer/admin click: the dedupe in
            // prepareRun fired. Treat as a coalesce onto that run.
            tx.executeWithoutResult(status -> signals.updateStatusAndRunId(
                    workspaceId, signalIds, RefreshSignalStatus.COALESCED, e.existingRunId()));
            meters.counter(MetricsConfig.REFRESH_SIGNALS_PROCESSED, "outcome", "coalesced")
                    .increment(signalIds.size());
            log.info(
                    "refresh.consumer.coalesced-on-race workspace={} runId={} signals={}",
                    workspaceId,
                    e.existingRunId(),
                    signalIds.size());
            return;
        }

        // Pre-CLAIM the still-PENDING rows so a concurrent consumer tick
        // can't re-dispatch them. Conditional: only flip rows that are
        // still PENDING — if the callback fired synchronously inside
        // beginAsyncForRefresh (test SyncTaskExecutor) the rows are
        // already CONSUMED and this is a correct no-op. updateCount tells
        // us which path we took for the log line below.
        int claimed = tx.execute(status -> signals.transitionStatusIfCurrentlyIn(
                workspaceId,
                signalIds,
                RefreshSignalStatus.PENDING,
                RefreshSignalStatus.CLAIMED,
                run.getId()));
        meters.counter(MetricsConfig.REFRESH_SIGNALS_PROCESSED, "outcome", "dispatched")
                .increment(signalIds.size());
        log.info(
                "refresh.consumer.dispatched workspace={} runId={} signals={} claimed={} window={}..{}",
                workspaceId,
                run.getId(),
                signalIds.size(),
                claimed,
                window.from(),
                window.to());
    }

    /**
     * Smallest covering window for the group's date hints, with two
     * safety rails:
     *
     * <ul>
     *   <li>If no signal carries a hint (TIME_ENTRY_DELETED, or pre-V10
     *       rows), use {@code [today - fallbackWindowDays, today]}.</li>
     *   <li>Cap the total span at {@code maxWindowDays} from the latest
     *       hint so a single ancient signal can't trigger a 365-day fetch.</li>
     * </ul>
     */
    DateRange computeWindow(List<RefreshSignal> group) {
        LocalDate earliest = null;
        LocalDate latest = null;
        for (RefreshSignal s : group) {
            String hint = s.getDateHint();
            if (hint == null || hint.isBlank()) continue;
            try {
                LocalDate parsed = LocalDate.parse(hint);
                if (earliest == null || parsed.isBefore(earliest)) earliest = parsed;
                if (latest == null || parsed.isAfter(latest)) latest = parsed;
            } catch (Exception ignored) {
                // Skip unparseable hints — the fallback covers us.
            }
        }
        LocalDate today = LocalDate.now();
        if (earliest == null) {
            return new DateRange(today.minusDays(fallbackWindowDays), today);
        }
        LocalDate to = latest;
        // Cap the span: earliest can't be more than maxWindowDays before to.
        LocalDate minFrom = to.minusDays(maxWindowDays);
        LocalDate from = earliest.isBefore(minFrom) ? minFrom : earliest;
        return new DateRange(from, to);
    }

    record DateRange(LocalDate from, LocalDate to) {}
}
