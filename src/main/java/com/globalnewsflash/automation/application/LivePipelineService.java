package com.globalnewsflash.automation.application;

import static com.globalnewsflash.shared.Hashing.sha256;

import com.globalnewsflash.automation.infrastructure.LiveAutomationMapper;
import com.globalnewsflash.automation.infrastructure.LiveAutomationMapper.EditorJob;
import com.globalnewsflash.automation.infrastructure.OperatingProperties;
import com.globalnewsflash.automation.infrastructure.PipelineMapper;
import com.globalnewsflash.automation.infrastructure.PipelineMapper.EventVersion;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.editorial.application.EditorialService;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.editorial.domain.EditorResponse;
import com.globalnewsflash.editorial.application.EditorFailure;
import com.globalnewsflash.ai.application.AiProviderSettings;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.publication.application.PublicationService;
import com.globalnewsflash.publication.infrastructure.TelegramProperties;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Live requests are bracketed by committed job/budget records; no DB lock spans an HTTP call. */
@Service
public class LivePipelineService {
    private final PipelineMapper pipeline;
    private final LiveAutomationMapper jobs;
    private final EditorialService editorial;
    private final PublicationService publications;
    private final BudgetService budgets;
    private final AiProviderSettings ai;
    private final TelegramProperties telegram;
    private final OperatingProperties operating;
    private final RuntimeProperties runtime;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public LivePipelineService(PipelineMapper pipeline, LiveAutomationMapper jobs, EditorialService editorial,
            PublicationService publications, BudgetService budgets, AiProviderSettings ai,
            TelegramProperties telegram, OperatingProperties operating, RuntimeProperties runtime,
            JsonMapper json, Clock clock, PlatformTransactionManager manager) {
        this.pipeline = pipeline; this.jobs = jobs; this.editorial = editorial; this.publications = publications;
        this.budgets = budgets; this.ai = ai; this.telegram = telegram; this.operating = operating;
        this.runtime = runtime; this.json = json; this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private record Prepared(EditorJob job, List<SourcePolicy> sources, PipelineService.PipelineRun result) {}

    public PipelineService.PipelineRun process(List<SourceMaterial> materials, EditorialPolicy policy,
                                              PipelineService.BudgetAllocation allocation) {
        if (!runtime.live()) throw new IllegalStateException("LIVE_EXTERNAL_ACCESS_DISABLED");
        if (materials == null || materials.isEmpty() || materials.stream().anyMatch(SourceMaterial::synthetic))
            throw new IllegalArgumentException("Real evidence is required");
        String eventKey = materials.getFirst().eventKey();
        if (materials.stream().anyMatch(m -> !eventKey.equals(m.eventKey())))
            throw new IllegalArgumentException("One event per live request is required");
        Prepared prepared = tx.execute(status -> prepare(materials, policy, allocation));
        if (prepared.result() != null) return prepared.result();
        var job = prepared.job();
        // An expired EDITING record is uncertain: recovery must not call the model again.
        try {
            var decision = editorial.evaluate(materials, prepared.sources(), policy);
            return tx.execute(status -> finish(job, materials, policy, decision, null));
        } catch (EditorFailure failure) {
            return tx.execute(status -> finish(job, materials, policy, null, failure));
        } catch (RuntimeException failure) {
            // The process may have failed after a billable request. Keep the reservation and audit.
            tx.executeWithoutResult(status -> uncertain(job, "EDITOR_OR_PERSISTENCE_UNCERTAIN"));
            throw new IllegalStateException("EDITOR_OR_PERSISTENCE_UNCERTAIN");
        }
    }

    private Prepared prepare(List<SourceMaterial> materials, EditorialPolicy policy,
                             PipelineService.BudgetAllocation allocation) {
        String key = materials.getFirst().eventKey();
        List<SourcePolicy> sources = sourcePolicies();
        // Admission is checked before storing externally supplied content.
        for (SourceMaterial material : materials) {
            var source = sources.stream().filter(s -> s.sourceId().equals(material.sourceId())).findFirst();
            List<String> denied = source.isEmpty() ? List.of("SOURCE_NOT_REGISTERED") : source.get().check(material);
            if (!denied.isEmpty()) return new Prepared(null, sources,
                    new PipelineService.PipelineRun(key, 0, "HELD", denied, null, false, false));
        }
        String fingerprint = fingerprint(materials, policy, sources);
        try { pipeline.insertEvent(key); } catch (DuplicateKeyException exists) { /* lock the existing event */ }
        int version = pipeline.lockEvent(key);
        EventVersion existing = pipeline.findVersion(key, fingerprint);
        if (existing != null) {
            var previous = jobs.lockJob(key, fingerprint);
            if (previous == null || !"RETRYABLE".equals(previous.state()) || version != existing.eventVersion())
                return new Prepared(null, sources, result(existing, true));
            List<String> rejected = editorial.validateEvidence(materials, sources, policy);
            if (!rejected.isEmpty()) return new Prepared(null, sources, new PipelineService.PipelineRun(
                    key, existing.eventVersion(), "HELD", rejected, null, false, false));
            if (allocation == null || allocation.estimatedMicros() < ai.estimatedRequestMicros())
                throw new IllegalArgumentException("A conservative request budget is required");
            String reservationId = "live:" + UUID.randomUUID();
            if (budgets.tryReserve(reservationId, allocation.periodId(), allocation.estimatedMicros()) == null)
                return new Prepared(null, sources, new PipelineService.PipelineRun(key, existing.eventVersion(),
                        "DEFERRED", List.of("BUDGET_EXHAUSTED"), null, false, false));
            budgets.markStarted(reservationId);
            long until = clock.millis() + operating.getJobLease().toMillis();
            jobs.restartJob(previous.id(), reservationId, until, clock.millis());
            var editing = new EditorialResult(false, List.of("EDITING"), null, key, policy.version(), clock.instant());
            jobs.restartVersion(key, existing.eventVersion(), json.writeValueAsString(editing), reservationId);
            return new Prepared(jobs.lockJobId(previous.id()), sources, null);
        }
        List<String> denied = editorial.validateEvidence(materials, sources, policy);
        var original = publications.list().stream()
                .filter(o -> !o.synthetic() && o.eventKey().equals(key) && o.target().equals(telegram.getChatId()))
                .filter(o -> "SENT".equals(o.state())).max(Comparator.comparingInt(o -> o.eventVersion())).orElse(null);
        boolean hasPrevious = pipeline.publicationCount(key) > 0;
        if (denied.isEmpty() && hasPrevious && (original == null || "HOLD".equals(operating.getRevisionMode())))
            denied = List.of(original == null ? "ORIGINAL_DELIVERY_NOT_CONFIRMED" : "REVISION_POLICY_NOT_READY");
        if (denied.isEmpty() && allocation == null) denied = List.of("BUDGET_NOT_CONFIGURED");
        if (denied.isEmpty() && allocation.estimatedMicros() < ai.estimatedRequestMicros())
            throw new IllegalArgumentException("A conservative request budget is required");
        String jobId = UUID.randomUUID().toString();
        String reservationId = null;
        if (denied.isEmpty()) {
            reservationId = "live:" + jobId;
            if (budgets.tryReserve(reservationId, allocation.periodId(), allocation.estimatedMicros()) == null) {
                reservationId = null;
                denied = List.of("BUDGET_EXHAUSTED");
            } else budgets.markStarted(reservationId);
        }
        // These conditions may change without a material/policy revision. Leave the inbox retryable.
        if (denied.stream().anyMatch(r -> Set.of("BUDGET_EXHAUSTED", "BUDGET_NOT_CONFIGURED",
                "ORIGINAL_DELIVERY_NOT_CONFIRMED").contains(r)))
            return new Prepared(null, sources, new PipelineService.PipelineRun(key, 0, "DEFERRED", denied, null, false, false));
        int next = version + 1;
        String state = denied.isEmpty() ? "EDITING" : "HELD";
        var decision = new EditorialResult(false, denied.isEmpty() ? List.of("EDITING") : denied,
                null, key, policy.version(), clock.instant());
        pipeline.insertVersion(new EventVersion(jobId, key, next, fingerprint, state, json.writeValueAsString(decision),
                policy.version(), null, json.writeValueAsString(materials), json.writeValueAsString(policy),
                json.writeValueAsString(sources), reservationId, clock.millis()));
        pipeline.updateVersion(key, next);
        for (SourceMaterial material : materials) {
            String materialFingerprint = sha256(materialIdentity(material));
            String id = sha256(material.sourceId() + "\n" + key + "\n" + materialFingerprint);
            if (pipeline.findMaterial(id) == null) pipeline.insertMaterial(id, material.sourceId(), key,
                    materialFingerprint, json.writeValueAsString(material), material.discoveredAt().toEpochMilli(), false);
        }
        var job = new EditorJob(jobId, key, next, fingerprint, state, reservationId,
                clock.millis() + operating.getJobLease().toMillis(), clock.millis(), clock.millis(),
                denied.isEmpty() ? null : denied.getFirst());
        jobs.insertJob(job);
        audit("LIVE_EDITOR_" + state, key, "version=" + next);
        return new Prepared(job, sources, denied.isEmpty() ? null : new PipelineService.PipelineRun(
                key, next, state, denied, null, false, false));
    }

    private PipelineService.PipelineRun finish(EditorJob original, List<SourceMaterial> materials,
            EditorialPolicy policy, EditorialResult decision, EditorFailure failure) {
        pipeline.lockEvent(original.eventKey());
        EditorJob job = jobs.lockJobId(original.id());
        if (!"EDITING".equals(job.state()) || job.leaseUntil() <= clock.millis()) {
            uncertain(job, "EDITOR_LEASE_EXPIRED");
            return new PipelineService.PipelineRun(job.eventKey(), job.eventVersion(), "UNCERTAIN",
                    List.of("EDITOR_LEASE_EXPIRED"), null, false, false);
        }
        EditorResponse.Usage usage = failure == null && decision.draft() != null
                ? decision.draft().usage() : failure == null ? null : failure.usage();
        if (failure != null && failure.chargedUnknown()) {
            uncertain(job, safeCode(failure.code()));
            return new PipelineService.PipelineRun(job.eventKey(), job.eventVersion(), "UNCERTAIN",
                    List.of(safeCode(failure.code())), null, false, false);
        }
        try {
            long cost = usage == null ? 0 : ai.actualMicros(usage);
            budgets.settle(job.reservationId(), cost);
        } catch (RuntimeException overage) {
            uncertain(job, "MODEL_COST_REQUIRES_RECONCILIATION");
            return new PipelineService.PipelineRun(job.eventKey(), job.eventVersion(), "UNCERTAIN",
                    List.of("MODEL_COST_REQUIRES_RECONCILIATION"), null, false, false);
        }
        if (failure != null && failure.usage() == null) {
            // A definite rejection before a billable response can be retried after configuration/backoff.
            var deferred = rejected(job, policy, safeCode(failure.code()));
            jobs.finishVersion(job.eventKey(), job.eventVersion(), "DEFERRED", json.writeValueAsString(deferred), null);
            jobs.finishJob(job.id(), "RETRYABLE", safeCode(failure.code()), clock.millis());
            return new PipelineService.PipelineRun(job.eventKey(), job.eventVersion(), "DEFERRED",
                    deferred.reasons(), null, false, false);
        }
        if (failure != null) decision = rejected(job, policy, safeCode(failure.code()));
        if (decision.eligible()) {
            List<String> reasons = editorial.validateStoredDraft(materials, sourcePolicies(), policy, decision.draft());
            if (!reasons.isEmpty()) decision = new EditorialResult(false, reasons, null,
                    job.eventKey(), policy.version(), clock.instant());
            else if (!runtime.live()) decision = rejected(job, policy, "LIVE_EXTERNAL_ACCESS_DISABLED");
            else if (pipeline.latestVersion(job.eventKey()) != job.eventVersion())
                decision = rejected(job, policy, "EVENT_VERSION_SUPERSEDED");
        }
        String publicationId = null;
        if (decision.eligible()) {
            var previous = publications.list().stream()
                    .filter(o -> !o.synthetic() && o.eventKey().equals(job.eventKey()) && o.target().equals(telegram.getChatId()))
                    .filter(o -> "SENT".equals(o.state())).max(Comparator.comparingInt(o -> o.eventVersion())).orElse(null);
            String kind = previous == null ? "SEND" : operating.getRevisionMode();
            if ("HOLD".equals(kind)) decision = rejected(job, policy, "REVISION_POLICY_NOT_READY");
            else publicationId = publications.enqueue(job.eventKey(), job.eventVersion(), telegram.getChatId(), kind,
                    decision.draft().body(), previous == null ? null : previous.id(), false).id();
        }
        String state = decision.eligible() ? "ELIGIBLE" : "HELD";
        jobs.finishVersion(job.eventKey(), job.eventVersion(), state, json.writeValueAsString(decision), publicationId);
        jobs.finishJob(job.id(), state, decision.reasons().isEmpty() ? null : decision.reasons().getFirst(), clock.millis());
        audit("LIVE_EDITOR_" + state, job.eventKey(), "version=" + job.eventVersion());
        return new PipelineService.PipelineRun(job.eventKey(), job.eventVersion(), state,
                decision.reasons(), publicationId, false, false);
    }

    public int recoverExpired() {
        int recovered = 0;
        for (var candidate : jobs.expiredJobs(clock.millis())) {
            Boolean changed = tx.execute(status -> {
                pipeline.lockEvent(candidate.eventKey());
                var job = jobs.lockJobId(candidate.id());
                if (!"EDITING".equals(job.state()) || job.leaseUntil() > clock.millis()) return false;
                uncertain(job, "EDITOR_PROCESS_INTERRUPTED"); return true;
            });
            if (Boolean.TRUE.equals(changed)) recovered++;
        }
        return recovered;
    }

    /** Account for an uncertain model request after checking provider usage; never publishes a lost response. */
    public Object resolveCost(String id, long actualMicros, String note) {
        if (note == null || note.isBlank() || note.length() > 1000 || actualMicros < 0)
            throw new IllegalArgumentException("A cost and reconciliation note are required");
        return tx.execute(status -> {
            var job = jobs.lockJobId(id);
            if (job == null || !"UNCERTAIN".equals(job.state())) throw new IllegalStateException("Job is not uncertain");
            budgets.reconcileUncertain(job.reservationId(), actualMicros);
            jobs.finishJob(id, "ACCOUNTED", "MANUAL_COST_RECONCILED", clock.millis());
            audit("LIVE_COST_RECONCILED", id, "actualMicros=" + actualMicros + ";note=" + note);
            return jobs.lockJobId(id);
        });
    }

    /** A separate, authenticated action is required to request a fresh response after cost reconciliation. */
    public PipelineService.PipelineRun retryJob(String id) {
        var snapshot = tx.execute(status -> {
            var job = jobs.lockJobId(id);
            if (job == null || !Set.of("ACCOUNTED", "RETRYABLE").contains(job.state()))
                throw new IllegalStateException("Only accounted or definitely rejected jobs can be retried");
            jobs.finishJob(id, "RETRYABLE", "OPERATOR_REQUESTED_RETRY", clock.millis());
            audit("LIVE_EDITOR_RETRY", id, "operator action");
            return pipeline.findNumber(job.eventKey(), job.eventVersion());
        });
        String periodId = "live-usd:" + clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        budgets.configurePeriod(periodId, operating.getDailyBudgetMicros(), operating.getDailyRequests());
        return process(Arrays.asList(json.readValue(snapshot.materialsPayload(), SourceMaterial[].class)),
                operating.policy(), new PipelineService.BudgetAllocation(periodId, ai.estimatedRequestMicros()));
    }

    private void uncertain(EditorJob job, String reason) {
        pipeline.lockEvent(job.eventKey());
        job = jobs.lockJobId(job.id());
        if (!"EDITING".equals(job.state())) return;
        var reservation = budgets.get(job.reservationId());
        if (reservation != null && "IN_FLIGHT".equals(reservation.state())) budgets.markUncertain(reservation.id());
        jobs.finishJob(job.id(), "UNCERTAIN", reason, clock.millis());
        var decision = new EditorialResult(false, List.of(reason), null, job.eventKey(), "uncertain", clock.instant());
        jobs.finishVersion(job.eventKey(), job.eventVersion(), "UNCERTAIN", json.writeValueAsString(decision), null);
        jobs.insertAlert(new LiveAutomationMapper.Alert(UUID.randomUUID().toString(), "MODEL_UNCERTAIN",
                job.eventKey(), reason, clock.millis()));
    }

    private PipelineService.PipelineRun result(EventVersion version, boolean duplicate) {
        var decision = json.readValue(version.decisionPayload(), EditorialResult.class);
        return new PipelineService.PipelineRun(version.eventKey(), version.eventVersion(), version.editorialState(),
                decision.reasons(), version.publicationId(), duplicate, false);
    }
    private EditorialResult rejected(EditorJob job, EditorialPolicy policy, String reason) {
        return new EditorialResult(false, List.of(reason), null, job.eventKey(), policy.version(), clock.instant());
    }
    private List<SourcePolicy> sourcePolicies() {
        return pipeline.sources().stream().filter(s -> !s.synthetic()).map(s -> new SourcePolicy(s.sourceId(),
                s.enabled(), s.approved(), false, s.licenseVersion(), s.permittedProducers().isBlank()
                ? Set.of() : Set.copyOf(Arrays.asList(s.permittedProducers().split(","))))).toList();
    }
    private String fingerprint(List<SourceMaterial> input, EditorialPolicy policy, List<SourcePolicy> sources) {
        return sha256(json.writeValueAsString(input.stream().map(this::materialIdentity).distinct().sorted().toList())
                + json.writeValueAsString(Arrays.asList(policy.version(), policy.maxAge(), policy.maxCharacters(),
                policy.minimumIndependentChains(), policy.allowedTopics().stream().sorted().toList(), policy.ready()))
                + json.writeValueAsString(sources.stream().filter(s -> input.stream().anyMatch(m -> m.sourceId().equals(s.sourceId())))
                    .sorted(Comparator.comparing(SourcePolicy::sourceId)).map(s -> Arrays.asList(s.sourceId(), s.enabled(),
                            s.approved(), s.licenseVersion(), s.permittedProducers().stream().sorted().toList())).toList())
                + telegram.getChatId() + operating.getRevisionMode());
    }
    private String materialIdentity(SourceMaterial m) {
        return json.writeValueAsString(Arrays.asList(m.sourceId(), m.externalId(), m.eventKey(), m.canonicalUrl(),
                m.headline(), m.topic(), m.producer(), m.evidenceChainId(), m.occurredAt(), m.publishedAt(),
                m.sourceUpdatedAt(), m.facts().stream().sorted(Comparator.comparing(SourceMaterial.Fact::id)).toList(), m.discoveryOnly()));
    }
    private void audit(String action, String subject, String details) {
        pipeline.audit(UUID.randomUUID().toString(), action, subject, details, clock.millis());
    }
    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : "EDITOR_ERROR";
    }
}
