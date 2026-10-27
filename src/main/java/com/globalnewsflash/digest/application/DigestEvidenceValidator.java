package com.globalnewsflash.digest.application;

import com.globalnewsflash.automation.port.out.BudgetRepository;
import com.globalnewsflash.automation.port.out.PipelineRepository;
import com.globalnewsflash.digest.domain.DigestEvidencePolicy;
import com.globalnewsflash.digest.domain.DigestItem;
import com.globalnewsflash.digest.port.in.DigestEvidenceUseCase;
import com.globalnewsflash.digest.port.out.DigestRepository;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.editorial.port.in.EditorialUseCase;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceRegistration;
import com.globalnewsflash.ingestion.port.out.SourceRegistry;
import com.globalnewsflash.publication.port.out.PublicationRepository;
import com.globalnewsflash.shared.port.out.SnapshotCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Read-only evidence checks; no publication service or network client is injected here. */
@Component
public final class DigestEvidenceValidator implements DigestEvidenceUseCase {
    private final SourceRegistry sourceRegistry;
    private final PipelineRepository pipeline;
    private final PublicationRepository publications;
    private final BudgetRepository budgets;
    private final DigestRepository digests;
    private final EditorialUseCase editorial;
    private final SnapshotCodec json;
    private final Clock clock;

    public DigestEvidenceValidator(PipelineRepository pipeline, PublicationRepository publications, BudgetRepository budgets,
            DigestRepository digests, EditorialUseCase editorial, SnapshotCodec json, Clock clock, SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
        this.pipeline = pipeline; this.publications = publications; this.budgets = budgets; this.digests = digests;
        this.editorial = editorial; this.json = json; this.clock = clock;
    }

    public List<DigestItem> eligibleItems(List<DigestItem> items, String target, Instant start, Instant end) {
        return evaluate(items, target, start, end).eligible();
    }
    public List<String> rejectionReasons(List<DigestItem> items, String target, Instant start, Instant end) {
        return evaluate(items, target, start, end).allEligible() ? List.of() : List.of("DIGEST_ITEM_EVIDENCE_REVOKED");
    }

    private Validation evaluate(List<DigestItem> items, String target, Instant start, Instant end) {
        if (!DigestEvidencePolicy.validWindow(items, target, start, end, clock.instant())) return new Validation(List.of(), false);
        // Deterministic lock order prevents digests selecting the same events in different orders.
        var latest = new HashMap<String, Integer>();
        items.stream().filter(Objects::nonNull).map(DigestItem::eventKey).filter(Objects::nonNull).distinct().sorted()
                .forEach(key -> latest.put(key, pipeline.lockEvent(key)));
        List<SourcePolicy> sources = sourceRegistry.lockSources().stream().filter(source -> !source.synthetic())
                .map(SourceRegistration::policy).toList();
        var eligible = new ArrayList<DigestItem>();
        var seenEvents = new java.util.HashSet<String>();
        var seenOperations = new java.util.HashSet<String>();
        for (DigestItem item : items) {
            if (item == null || !seenEvents.add(item.eventKey()) || !seenOperations.add(item.operationId())) continue;
            if (eligible(item, target, start, end, latest, sources)) eligible.add(item);
        }
        return new Validation(List.copyOf(eligible), eligible.size() == items.size());
    }

    private boolean eligible(DigestItem item, String target, Instant start, Instant end,
            HashMap<String, Integer> latest, List<SourcePolicy> sources) {
        var operation = publications.find(item.operationId());
        if (!DigestEvidencePolicy.currentItem(item, target, start, end, clock.instant(), operation,
                operation == null ? null : digests.sentAt(operation.id()), latest.get(item.eventKey()))) return false;
        var version = pipeline.findNumber(item.eventKey(), item.eventVersion());
        var reservation = version == null || version.budgetReservationId() == null ? null : budgets.find(version.budgetReservationId());
        if (!DigestEvidencePolicy.accountedVersion(version, reservation, operation.id())) return false;
        try {
            var decision = json.readValue(version.decisionPayload(), EditorialResult.class);
            List<SourceMaterial> materials = Arrays.asList(json.readValue(version.materialsPayload(), SourceMaterial[].class));
            var previousSources = Arrays.asList(json.readValue(version.sourcesPayload(), SourcePolicy[].class));
            if (!DigestEvidencePolicy.consistentSnapshot(item, decision, materials, previousSources, sources)) return false;
            var policy = json.readValue(version.policyPayload(), EditorialPolicy.class);
            return editorial.validateStoredDraft(materials, sources, policy, decision.draft()).isEmpty();
        } catch (RuntimeException invalidSnapshot) {
            return false;
        }
    }
    private record Validation(List<DigestItem> eligible, boolean allEligible) {}
}
