package com.globalnewsflash.automation.application;

import static com.globalnewsflash.shared.Hashing.sha256;

import com.globalnewsflash.automation.infrastructure.PipelineMapper;
import com.globalnewsflash.automation.infrastructure.PipelineMapper.EventVersion;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.editorial.application.EditorialService;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.publication.application.PublicationService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PipelineService {
    private final PipelineMapper mapper;
    private final EditorialService editorial;
    private final PublicationService publication;
    private final JsonMapper json;
    private final Clock clock;
    private final BudgetService budgets;
    private final RuntimeProperties runtime;

    public PipelineService(PipelineMapper mapper, EditorialService editorial, PublicationService publication,
                           JsonMapper json, Clock clock, BudgetService budgets, RuntimeProperties runtime) {
        this.mapper = mapper;
        this.editorial = editorial;
        this.publication = publication;
        this.json = json;
        this.clock = clock;
        this.budgets = budgets;
        this.runtime = runtime;
    }

    public record PipelineRun(String eventKey, int eventVersion, String state, List<String> reasons,
                              String publicationId, boolean duplicate, boolean synthetic) {}
    public record BudgetAllocation(String periodId, long estimatedMicros) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PipelineRun process(List<SourceMaterial> input, EditorialPolicy policy, BudgetAllocation budget) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("At least one material is required");
        if (policy == null) throw new IllegalArgumentException("An explicit policy is required");
        if (input.stream().anyMatch(m -> !m.synthetic())) {
            throw new IllegalArgumentException("This build accepts synthetic materials only");
        }
        String eventKey = input.getFirst().eventKey();
        if (input.stream().anyMatch(m -> !eventKey.equals(m.eventKey()))) {
            throw new IllegalArgumentException("One event per processing request is required");
        }
        List<SourcePolicy> policies = sourcePolicies(true);
        // Recheck admission before storing any supplied material content.
        List<String> admission = new ArrayList<>();
        for (var material : input) {
            var source = policies.stream().filter(p -> p.sourceId().equals(material.sourceId())).findFirst();
            if (source.isEmpty()) admission.add("SOURCE_NOT_REGISTERED");
            else admission.addAll(source.get().check(material));
        }
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
        String state = decision.eligible() ? "ELIGIBLE" :
                decision.reasons().stream().anyMatch(r -> r.contains("EXPIRED") || r.contains("STALE")) ? "EXPIRED" : "HELD";
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

    public List<PipelineMapper.SourceRow> sources() { return mapper.sources(); }
    public List<EventVersion> versions() { return mapper.versions(); }
    public long materialCount() { return mapper.materialCount(); }
    public long auditCount() { return mapper.auditCount(); }

    @Transactional
    public void setSourceEnabled(String id, boolean enabled) {
        var source = sources().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown source"));
        if (enabled && !source.synthetic() && (!runtime.live() || !source.approved()
                || source.licenseVersion().isBlank() || "pending".equals(source.licenseVersion())
                || source.permittedProducers().isBlank()))
            throw new IllegalArgumentException("Real sources require live mode, approval, license and permitted producers");
        mapper.enableSource(id, enabled);
        audit(enabled ? "SOURCE_ENABLED" : "SOURCE_DISABLED", id, "operator action");
    }

    public List<SourcePolicy> sourcePolicies(boolean synthetic) {
        return sources().stream().filter(s -> s.synthetic() == synthetic).map(s -> new SourcePolicy(s.sourceId(),
                s.enabled(), s.approved(), s.synthetic(), s.licenseVersion(),
                s.permittedProducers().isBlank() ? Set.of() : Set.copyOf(Arrays.asList(s.permittedProducers().split(",")))))
                .toList();
    }

    private String materialIdentity(SourceMaterial m) {
        // discoveredAt is deliberately excluded: rediscovery is not a material revision.
        return json.writeValueAsString(Arrays.asList(m.sourceId(), m.externalId(), m.eventKey(), m.canonicalUrl(),
                m.headline(), m.topic(), m.producer(), m.evidenceChainId(), m.occurredAt(), m.publishedAt(),
                m.sourceUpdatedAt(), m.facts().stream().sorted(Comparator.comparing(SourceMaterial.Fact::id)).toList(),
                m.discoveryOnly(), m.synthetic()));
    }

    private void audit(String action, String subject, String details) {
        mapper.audit(UUID.randomUUID().toString(), action, subject, details, clock.millis());
    }

}
