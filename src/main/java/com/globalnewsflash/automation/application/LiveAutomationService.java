package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.domain.Alert;
import com.globalnewsflash.automation.domain.AutomationReadiness;
import com.globalnewsflash.automation.port.in.AutomationUseCase;
import com.globalnewsflash.automation.port.in.BudgetUseCase;
import com.globalnewsflash.automation.port.in.LivePipelineUseCase;
import com.globalnewsflash.automation.port.in.PipelineUseCase;
import com.globalnewsflash.automation.port.out.LiveAutomationRepository;
import com.globalnewsflash.automation.port.out.OperatingSettings;
import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.digest.port.in.DigestUseCase;
import com.globalnewsflash.editorial.port.out.ModelPricing;
import com.globalnewsflash.ingestion.port.in.IngestionUseCase;
import com.globalnewsflash.ingestion.port.out.IngestionSettings;
import com.globalnewsflash.ingestion.port.out.SourceRegistry;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import com.globalnewsflash.publication.port.out.PublicationTarget;
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
public class LiveAutomationService implements AutomationUseCase {
    private final RuntimeSettings runtime;
    private final OperatingSettings operating;
    private final ModelPricing ai;
    private final PublicationTarget telegram;
    private final IngestionSettings ingestionProperties;
    private final IngestionUseCase ingestion;
    private final LivePipelineUseCase pipeline;
    private final PublicationUseCase publications;
    private final BudgetUseCase budgets;
    private final LiveAutomationRepository mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final DigestUseCase digest;
    private final SourceRegistry evidence;

    public LiveAutomationService(RuntimeSettings runtime, OperatingSettings operating,
            ModelPricing ai, PublicationTarget telegram, IngestionSettings ingestionProperties,
            IngestionUseCase ingestion, LivePipelineUseCase pipeline, PublicationUseCase publications,
            BudgetUseCase budgets, LiveAutomationRepository mapper, Clock clock, PlatformTransactionManager manager,
            DigestUseCase digest, SourceRegistry evidence) {
        this.runtime = runtime; this.operating = operating; this.ai = ai; this.telegram = telegram;
        this.ingestionProperties = ingestionProperties; this.ingestion = ingestion; this.pipeline = pipeline;
        this.publications = publications; this.budgets = budgets; this.mapper = mapper; this.clock = clock;
        tx = new TransactionTemplate(manager);
        this.digest = digest;
        this.evidence = evidence;
    }

    private List<String> editingReasons() {
        var aiReasons = ai.readinessReasons();
        Long estimate = null;
        boolean invalidEstimate = false;
        if (aiReasons.isEmpty()) {
            try { estimate = ai.estimatedRequestMicros(); }
            catch (RuntimeException invalid) { invalidEstimate = true; }
        }
        boolean approvedSource = !runtime.live() || evidence.sources().stream().anyMatch(s -> !s.synthetic() && s.enabled() && s.approved());
        return new AutomationReadiness(runtime.live(), operating.problems(), aiReasons, estimate, invalidEstimate,
                operating.getDailyBudgetMicros(), ingestionProperties.isEnabled(), approvedSource, telegram.getChatId(),
                operating.getJobLease(), ai.getTimeout(), ingestionProperties.getInboxLeaseSeconds() * 1000L,
                operating.getCycleLease(), ingestionProperties.getRequestTimeoutSeconds() * 1000L).reasons();
    }

    public List<String> readinessReasons() {
        var reasons = new ArrayList<>(editingReasons());
        // Explicit preview mode can edit without delivery; enabled delivery must be configured before spending.
        if (telegram.isEnabled() && !telegram.isConfigured()) reasons.add("TELEGRAM_CONFIGURATION_REQUIRED");
        return List.copyOf(reasons);
    }

    public Map<String, Object> status() {
        var reasons = readinessReasons();
        var status = new LinkedHashMap<String, Object>();
        status.put("schedulerEnabled", operating.isEnabled());
        var publicationReasons = new ArrayList<String>();
        if (!runtime.live()) publicationReasons.add("LIVE_EXTERNAL_ACCESS_DISABLED");
        if (!telegram.isEnabled()) publicationReasons.add("TELEGRAM_DELIVERY_DISABLED");
        if (!telegram.isConfigured()) publicationReasons.add("TELEGRAM_CONFIGURATION_REQUIRED");
        var allReasons = new java.util.LinkedHashSet<>(reasons);
        allReasons.addAll(publicationReasons);
        boolean ready = allReasons.isEmpty();
        status.put("ready", ready);
        status.put("reasons", List.copyOf(allReasons));
        status.put("editingReady", editingReasons().isEmpty());
        status.put("publicationReady", publicationReasons.isEmpty());
        status.put("publicationReasons", publicationReasons);
        status.put("running", ready && operating.isEnabled() && !publications.isPaused());
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
        var results = new ArrayList<PipelineUseCase.PipelineRun>();
        try {
            pipeline.recoverExpired();
            var polling = ingestion.poll(() -> renew(runId));
            for (var source : polling.sources()) {
                if (source.code() != null) mapper.insertAlert(new Alert(
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
                            new PipelineUseCase.BudgetAllocation(periodId, ai.estimatedRequestMicros()));
                    if ("DEFERRED".equals(result.state())) ingestion.retry(item.id(), result.reasons().getFirst());
                    else ingestion.complete(item.id(), result.state(), result.reasons().isEmpty() ? "QUEUED" : result.reasons().getFirst());
                    results.add(result);
                    processed++;
                } catch (RuntimeException failure) {
                    // A committed EDITING job prevents a duplicate model call on a later inbox attempt.
                    ingestion.retry(item.id(), "PIPELINE_PROCESSING_FAILED");
                    mapper.insertAlert(new Alert(UUID.randomUUID().toString(),
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
            mapper.insertAlert(new Alert(UUID.randomUUID().toString(), "AUTOMATION_ERROR",
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
    public Object requeue(String id) {
        tx.executeWithoutResult(status -> pipeline.requestRetry(ingestion.requeue(id)));
        return ingestion.inboxStatus();
    }
    public Object articleFailures() { return ingestion.articleFailures(); }
    public Object jobs() { return mapper.jobs(); }
    public Object alerts() { return mapper.alerts(); }

    private void renew(String id) {
        if (mapper.renewCycle(id, clock.millis() + operating.getCycleLease().toMillis(), clock.millis()) != 1)
            throw new IllegalStateException("AUTOMATION_LEASE_LOST");
    }
}
