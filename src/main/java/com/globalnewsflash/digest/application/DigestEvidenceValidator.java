package com.globalnewsflash.digest.application;

import com.globalnewsflash.automation.infrastructure.BudgetMapper;
import com.globalnewsflash.automation.infrastructure.PipelineMapper;
import com.globalnewsflash.digest.infrastructure.DigestMapper;
import com.globalnewsflash.digest.infrastructure.DigestMapper.DigestItem;
import com.globalnewsflash.editorial.application.EditorialService;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.publication.infrastructure.PublicationMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Read-only evidence checks; no publication service or network client is injected here. */
@Component
public final class DigestEvidenceValidator {
    private final PipelineMapper pipeline;
    private final PublicationMapper publications;
    private final BudgetMapper budgets;
    private final DigestMapper digests;
    private final EditorialService editorial;
    private final JsonMapper json;
    private final Clock clock;

    public DigestEvidenceValidator(PipelineMapper pipeline, PublicationMapper publications, BudgetMapper budgets,
            DigestMapper digests, EditorialService editorial, JsonMapper json, Clock clock) {
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
        if (items == null || items.isEmpty() || items.size() > 500 || target == null || start == null || end == null
                || !start.isBefore(end) || end.isAfter(clock.instant())) return new Validation(List.of(), false);
        // Deterministic lock order prevents digests selecting the same events in different orders.
        var latest = new HashMap<String, Integer>();
        items.stream().filter(Objects::nonNull).map(DigestItem::eventKey).filter(Objects::nonNull).distinct().sorted()
                .forEach(key -> latest.put(key, pipeline.lockEvent(key)));
        List<SourcePolicy> sources = pipeline.lockSources().stream().filter(source -> !source.synthetic())
                .map(source -> new SourcePolicy(source.sourceId(), source.enabled(), source.approved(), false,
                        source.licenseVersion(), source.permittedProducers().isBlank() ? Set.of()
                                : Set.copyOf(Arrays.asList(source.permittedProducers().split(","))))).toList();
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
        if (item.operationId() == null || item.eventKey() == null || item.eventKey().startsWith("digest:")
                || item.body() == null || item.body().isBlank() || item.sentAt() == null
                || item.sentAt().isBefore(start) || item.sentAt().isAfter(end)
                || item.sentAt().isBefore(clock.instant().minus(Duration.ofHours(24)))) return false;
        var operation = publications.find(item.operationId());
        if (operation == null || operation.synthetic() || !"SENT".equals(operation.state())
                || operation.messageId() == null || operation.messageId().isBlank() || operation.messageId().startsWith("sim-")
                || !target.equals(operation.target()) || !item.eventKey().equals(operation.eventKey())
                || item.eventVersion() != operation.eventVersion() || !item.body().equals(operation.body())
                || !item.sentAt().equals(digests.sentAt(operation.id()))
                || !Objects.equals(latest.get(item.eventKey()), item.eventVersion())) return false;
        var version = pipeline.findNumber(item.eventKey(), item.eventVersion());
        if (version == null || !"ELIGIBLE".equals(version.editorialState())
                || !operation.id().equals(version.publicationId())) return false;
        var reservation = version.budgetReservationId() == null ? null : budgets.find(version.budgetReservationId());
        if (reservation == null || !"SETTLED".equals(reservation.state())) return false;
        try {
            var decision = json.readValue(version.decisionPayload(), EditorialResult.class);
            if (!decision.eligible() || decision.draft() == null || decision.draft().synthetic()
                    || !item.body().equals(decision.draft().body())) return false;
            List<SourceMaterial> materials = Arrays.asList(json.readValue(version.materialsPayload(), SourceMaterial[].class));
            if (materials.isEmpty() || materials.stream().anyMatch(material -> material.synthetic()
                    || !item.eventKey().equals(material.eventKey()))) return false;
            var previousSources = Arrays.asList(json.readValue(version.sourcesPayload(), SourcePolicy[].class));
            if (previousSources.stream().anyMatch(previous -> sources.stream().noneMatch(previous::equals))) return false;
            var policy = json.readValue(version.policyPayload(), EditorialPolicy.class);
            return editorial.validateStoredDraft(materials, sources, policy, decision.draft()).isEmpty();
        } catch (RuntimeException invalidSnapshot) {
            return false;
        }
    }
    private record Validation(List<DigestItem> eligible, boolean allEligible) {}
}
