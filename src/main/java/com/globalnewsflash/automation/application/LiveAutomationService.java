package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.infrastructure.LiveAutomationMapper;
import com.globalnewsflash.automation.infrastructure.OperatingProperties;
import com.globalnewsflash.automation.infrastructure.PipelineMapper;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.editorial.infrastructure.OpenAiProperties;
import com.globalnewsflash.digest.application.DigestService;
import com.globalnewsflash.ingestion.infrastructure.LiveIngestionProperties;
import com.globalnewsflash.ingestion.application.LiveIngestionService;
import com.globalnewsflash.publication.application.PublicationService;
import com.globalnewsflash.publication.infrastructure.TelegramProperties;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One bounded cycle, also exposed through the authenticated operations API. */
@Service
public class LiveAutomationService {
    private final RuntimeProperties runtime;
    private final OperatingProperties operating;
    private final OpenAiProperties openai;
    private final TelegramProperties telegram;
    private final LiveIngestionProperties ingestionProperties;
    private final LiveIngestionService ingestion;
    private final LivePipelineService pipeline;
    private final PublicationService publications;
    private final BudgetService budgets;
    private final LiveAutomationMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final DigestService digest;
    private final PipelineMapper evidence;

    public LiveAutomationService(RuntimeProperties runtime, OperatingProperties operating,
            OpenAiProperties openai, TelegramProperties telegram, LiveIngestionProperties ingestionProperties,
            LiveIngestionService ingestion, LivePipelineService pipeline, PublicationService publications,
            BudgetService budgets, LiveAutomationMapper mapper, Clock clock, PlatformTransactionManager manager,
            DigestService digest, PipelineMapper evidence) {
        this.runtime = runtime; this.operating = operating; this.openai = openai; this.telegram = telegram;
        this.ingestionProperties = ingestionProperties; this.ingestion = ingestion; this.pipeline = pipeline;
        this.publications = publications; this.budgets = budgets; this.mapper = mapper; this.clock = clock;
        tx = new TransactionTemplate(manager);
        this.digest = digest;
        this.evidence = evidence;
    }

    public List<String> readinessReasons() {
        var reasons = new ArrayList<String>();
        if (!runtime.live()) reasons.add("LIVE_EXTERNAL_ACCESS_DISABLED");
        reasons.addAll(operating.problems());
        reasons.addAll(openai.readinessReasons());
        if (openai.readinessReasons().isEmpty()) {
            try {
                if (openai.estimatedRequestMicros() > operating.getDailyBudgetMicros())
                    reasons.add("REQUEST_RESERVATION_EXCEEDS_DAILY_BUDGET");
            } catch (RuntimeException invalidPrice) { reasons.add("OPENAI_COST_LIMIT_INVALID"); }
        }
        if (!ingestionProperties.isEnabled()) reasons.add("INGESTION_DISABLED");
        if (runtime.live() && evidence.sources().stream().noneMatch(s -> !s.synthetic() && s.enabled() && s.approved()))
            reasons.add("NO_APPROVED_SOURCE_ENABLED");
        if (telegram.getChatId() == null || telegram.getChatId().isBlank()) reasons.add("TELEGRAM_TARGET_MISSING");
        if (operating.getJobLease() != null && openai.getTimeout() != null
                && operating.getJobLease().compareTo(openai.getTimeout().plusSeconds(30)) <= 0)
            reasons.add("EDITOR_LEASE_TOO_SHORT");
        if (operating.getJobLease() != null && ingestionProperties.getInboxLeaseSeconds() * 1000L
                <= operating.getJobLease().toMillis()) reasons.add("INBOX_LEASE_TOO_SHORT");
        if (operating.getCycleLease() != null && operating.getCycleLease().toMillis()
                <= ingestionProperties.getRequestTimeoutSeconds() * 1000L + 30_000L)
            reasons.add("CYCLE_LEASE_TOO_SHORT");
        return List.copyOf(reasons);
    }

    public Map<String, Object> status() {
        var status = new LinkedHashMap<String, Object>();
        status.put("schedulerEnabled", operating.isEnabled());
        status.put("ready", readinessReasons().isEmpty());
        status.put("reasons", readinessReasons());
        status.put("telegramDeliveryEnabled", telegram.isEnabled());
        status.put("telegramConfigured", telegram.isConfigured());
        status.put("revisionMode", operating.getRevisionMode());
        status.put("runs", mapper.runs());
        return status;
    }

    public Map<String, Object> runOnce() {
        List<String> problems = readinessReasons();
        if (!problems.isEmpty()) return Map.of("state", "NOT_READY", "reasons", problems);
        if (publications.isPaused()) return Map.of("state", "PAUSED");
        String runId = UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(tx.execute(status -> {
            if (mapper.claimCycle(runId, clock.millis() + operating.getCycleLease().toMillis(), clock.millis()) != 1) return false;
            mapper.startRun(runId, clock.millis()); return true;
        }));
        if (!acquired) return Map.of("state", "BUSY");
        int processed = 0;
        String state = "COMPLETED";
        String error = null;
        var results = new ArrayList<PipelineService.PipelineRun>();
        try {
            pipeline.recoverExpired();
            var polling = ingestion.poll(() -> renew(runId));
            for (var source : polling.sources()) {
                if ("FAILED".equals(source.state())) mapper.insertAlert(new LiveAutomationMapper.Alert(
                        UUID.randomUUID().toString(), "SOURCE_ERROR", source.sourceId(), source.code(), clock.millis()));
            }
            renew(runId);
            String periodId = "live-usd:" + clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            budgets.configurePeriod(periodId, operating.getDailyBudgetMicros(), operating.getDailyRequests());
            for (int index = 0; index < operating.getBatchSize(); index++) {
                if (publications.isPaused()) break;
                renew(runId);
                // Claim immediately before processing, so later items cannot outlive their inbox lease.
                var pending = ingestion.pending(1);
                if (pending.isEmpty()) break;
                var item = pending.getFirst();
                try {
                    var result = pipeline.process(List.of(item.material()), operating.policy(),
                            new PipelineService.BudgetAllocation(periodId, openai.estimatedRequestMicros()));
                    if ("DEFERRED".equals(result.state())) ingestion.retry(item.id(), result.reasons().getFirst());
                    else ingestion.complete(item.id(), result.state(), result.reasons().isEmpty() ? "QUEUED" : result.reasons().getFirst());
                    results.add(result);
                    processed++;
                } catch (RuntimeException failure) {
                    // A committed EDITING job prevents a duplicate model call on a later inbox attempt.
                    ingestion.retry(item.id(), "PIPELINE_PROCESSING_FAILED");
                    mapper.insertAlert(new LiveAutomationMapper.Alert(UUID.randomUUID().toString(),
                            "PIPELINE_ERROR", item.material().eventKey(), "PIPELINE_PROCESSING_FAILED", clock.millis()));
                }
            }
            if (!publications.isPaused()) publications.processDueBatch();
            digest.runDue();
            if (!publications.isPaused()) publications.processDueBatch();
            return Map.of("id", runId, "state", "COMPLETED", "processed", processed,
                    "polling", polling, "results", results, "telegramDeliveryEnabled", telegram.isEnabled());
        } catch (RuntimeException failure) {
            state = "FAILED"; error = "AUTOMATION_CYCLE_FAILED";
            mapper.insertAlert(new LiveAutomationMapper.Alert(UUID.randomUUID().toString(), "AUTOMATION_ERROR",
                    runId, error, clock.millis()));
            return Map.of("id", runId, "state", state, "reason", error, "processed", processed);
        } finally {
            final String finalState = state, finalError = error;
            final int finalProcessed = processed;
            tx.executeWithoutResult(status -> {
                mapper.finishRun(runId, clock.millis(), finalState, finalProcessed, finalError);
                mapper.releaseCycle(runId);
            });
        }
    }

    public Object poll() {
        if (!runtime.live()) throw new IllegalStateException("LIVE_EXTERNAL_ACCESS_DISABLED");
        return ingestion.poll();
    }
    public Object ingestionStatus() { return ingestion.status(); }
    public Object inboxStatus() { return ingestion.inboxStatus(); }
    public Object requeue(String id) { ingestion.requeue(id); return ingestion.inboxStatus(); }
    public Object jobs() { return mapper.jobs(); }
    public Object alerts() { return mapper.alerts(); }

    private void renew(String id) {
        if (mapper.renewCycle(id, clock.millis() + operating.getCycleLease().toMillis(), clock.millis()) != 1)
            throw new IllegalStateException("AUTOMATION_LEASE_LOST");
    }
}
