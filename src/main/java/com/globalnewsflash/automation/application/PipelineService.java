package com.globalnewsflash.automation.application;

import static com.globalnewsflash.shared.Hashing.sha256;

import com.globalnewsflash.automation.domain.EventVersion;
import com.globalnewsflash.automation.domain.PipelinePolicy;
import com.globalnewsflash.automation.port.in.BudgetUseCase;
import com.globalnewsflash.automation.port.in.PipelineUseCase;
import com.globalnewsflash.automation.port.out.PipelineRepository;
import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.editorial.port.in.EditorialUseCase;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceRegistration;
import com.globalnewsflash.ingestion.port.out.SourceRegistry;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import com.globalnewsflash.shared.port.out.SnapshotCodec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PipelineService implements PipelineUseCase {
    private final SourceRegistry sourceRegistry;
    private final PipelineRepository mapper;
    private final EditorialUseCase editorial;
    private final PublicationUseCase publication;
    private final SnapshotCodec json;
    private final Clock clock;
    private final BudgetUseCase budgets;
    private final RuntimeSettings runtime;

    public PipelineService(PipelineRepository mapper, EditorialUseCase editorial, PublicationUseCase publication,
                           SnapshotCodec json, Clock clock, BudgetUseCase budgets, RuntimeSettings runtime, SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
        this.mapper = mapper;
        this.editorial = editorial;
        this.publication = publication;
        this.json = json;
        this.clock = clock;
        this.budgets = budgets;
        this.runtime = runtime;
    }


    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PipelineRun process(List<SourceMaterial> input, EditorialPolicy policy, BudgetAllocation budget) {
        String eventKey = PipelinePolicy.requireSyntheticRequest(input, policy);
        List<SourcePolicy> policies = sourcePolicies(true);
        // Recheck admission before storing any supplied material content.
        List<String> admission = PipelinePolicy.admission(input, policies, false);
        if (!admission.isEmpty()) {
            audit("ADMISSION_REJECTED", eventKey, String.join(",", admission));
            return new PipelineRun(eventKey, 0, "HELD", List.copyOf(admission), null, false, true);
        }
        List<SourcePolicy> relevantPolicies = policies.stream()
                .filter(p -> input.stream().anyMatch(m -> p.sourceId().equals(m.sourceId())))
                .sorted(Comparator.comparing(SourcePolicy::sourceId)).toList();
        String fingerprint = sha256(json.writeValueAsString(input.stream().map(this::materialIdentity).distinct().sorted().toList())
                + json.writeValueAsString(Arrays.asList(policy.version(), policy.maxAge(), policy.maxCharacters(),
                policy.minimumIndependentChains(), policy.allowedTopics().stream().sorted().toList(), policy.ready()))
                + json.writeValueAsString(relevantPolicies.stream().map(p -> Arrays.asList(p.sourceId(), p.enabled(),
                p.approved(), p.synthetic(), p.licenseVersion(), p.permittedProducers().stream().sorted().toList())).toList())
                + json.writeValueAsString(budget));
        try { mapper.insertEvent(eventKey); }
        catch (DuplicateKeyException alreadyExists) { /* existing event will be locked below */ }
        int latest = mapper.lockEvent(eventKey);
        EventVersion existing = mapper.findVersion(eventKey, fingerprint);
        if (existing != null) {
            EditorialResult result = json.readValue(existing.decisionPayload(), EditorialResult.class);
            return new PipelineRun(eventKey, existing.eventVersion(), existing.editorialState(), result.reasons(),
                    existing.publicationId(), true, true);
        }
        for (var material : input) {
            String materialFingerprint = sha256(materialIdentity(material));
            String id = sha256(material.sourceId() + "\n" + eventKey + "\n" + materialFingerprint);
            if (mapper.findMaterial(id) == null) {
                mapper.insertMaterial(id, material.sourceId(), eventKey, materialFingerprint,
                        json.writeValueAsString(material), material.discoveredAt().toEpochMilli(), true);
            }
        }
        String reservationId = null;
        EditorialResult decision;
        if (!policy.configured()) {
            decision = editorial.evaluate(input, policies, policy);
        } else if (budget == null || budgets.report().stream().noneMatch(p -> p.id().equals(budget.periodId()))) {
            decision = new EditorialResult(false, List.of("BUDGET_NOT_CONFIGURED"), null, eventKey, policy.version(), clock.instant());
        } else {
            var reservation = budgets.tryReserve("edit:" + sha256(eventKey + fingerprint), budget.periodId(), budget.estimatedMicros());
            if (reservation == null) {
                decision = new EditorialResult(false, List.of("BUDGET_EXHAUSTED"), null, eventKey, policy.version(), clock.instant());
            } else {
                reservationId = reservation.id();
                budgets.markStarted(reservationId);
                decision = editorial.evaluate(input, policies, policy);
                budgets.settle(reservationId, 0); // Synthetic editor has no real token cost.
            }
        }
        if (mapper.publicationCount(eventKey) > 0 && decision.eligible()) {
            // Choosing edit/update/correction is an unresolved product policy, not a retry.
            decision = new EditorialResult(false, List.of("REVISION_POLICY_NOT_READY"), null,
                    eventKey, policy.version(), clock.instant());
        }
        int version = latest + 1;
        String state = PipelinePolicy.editorialState(decision.eligible(), decision.reasons());
        String publicationId = decision.eligible()
                ? publication.enqueue(eventKey, version, "synthetic-channel", "SEND", decision.draft().body(), null, true).id()
                : null;
        mapper.insertVersion(new EventVersion(UUID.randomUUID().toString(), eventKey, version, fingerprint,
                state, json.writeValueAsString(decision), policy.version(), publicationId,
                json.writeValueAsString(input), json.writeValueAsString(policy), json.writeValueAsString(relevantPolicies),
                reservationId, clock.millis()));
        mapper.updateVersion(eventKey, version);
        audit("EDITORIAL_" + state, eventKey, "version=" + version + ";policy=" + policy.version());
        return new PipelineRun(eventKey, version, state, decision.reasons(), publicationId, false, true);
    }

    public List<SourceRegistration> sources() { return sourceRegistry.sources(); }
    public List<EventVersion> versions() { return mapper.versions(); }
    public long materialCount() { return mapper.materialCount(); }
    public long auditCount() { return mapper.auditCount(); }

    @Transactional
    public void setSourceEnabled(String id, boolean enabled) {
        var source = sources().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown source"));
        source.requireEnablement(enabled, runtime.live());
        sourceRegistry.enableSource(id, enabled);
        audit(enabled ? "SOURCE_ENABLED" : "SOURCE_DISABLED", id, "operator action");
    }

    public List<SourcePolicy> sourcePolicies(boolean synthetic) {
        return sources().stream().filter(s -> s.synthetic() == synthetic).map(SourceRegistration::policy).toList();
    }

    private String materialIdentity(SourceMaterial material) {
        return json.writeValueAsString(material.versionIdentity(true, true));
    }

    private void audit(String action, String subject, String details) {
        mapper.audit(UUID.randomUUID().toString(), action, subject, details, clock.millis());
    }

}
