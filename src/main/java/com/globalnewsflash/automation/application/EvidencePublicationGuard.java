package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.infrastructure.PipelineMapper;
import com.globalnewsflash.editorial.application.EditorialService;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.publication.application.PublicationGuard;
import com.globalnewsflash.publication.domain.PublicationOperation;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Fails closed on orphaned tasks, withdrawn sources, expiry and superseded evidence. */
@Component
@ConditionalOnProperty(name = "news.evidence-guard.enabled", havingValue = "true", matchIfMissing = true)
public class EvidencePublicationGuard implements PublicationGuard {
    private final PipelineMapper mapper;
    private final EditorialService editorial;
    private final JsonMapper json;
    private final BudgetService budgets;

    public EvidencePublicationGuard(PipelineMapper mapper, EditorialService editorial, JsonMapper json, BudgetService budgets) {
        this.mapper = mapper; this.editorial = editorial; this.json = json; this.budgets = budgets;
    }

    @Override
    public List<String> rejectionReasons(PublicationOperation operation) {
        var version = mapper.findNumber(operation.eventKey(), operation.eventVersion());
        if (version == null || !"ELIGIBLE".equals(version.editorialState())) return List.of("NO_ELIGIBLE_EVIDENCE");
        var reservation = version.budgetReservationId() == null ? null : budgets.get(version.budgetReservationId());
        if (reservation == null || !"SETTLED".equals(reservation.state())) return List.of("NO_ACCOUNTED_EDITOR_REQUEST");
        if (!Objects.equals(mapper.lockEvent(operation.eventKey()), operation.eventVersion())) {
            return List.of("EVENT_VERSION_SUPERSEDED");
        }
        var originalDecision = json.readValue(version.decisionPayload(), EditorialResult.class);
        // SEND body is immutable and must be exactly the validated draft. Other operation types
        // need a separately reviewed amendment contract and are currently rejected by this guard.
        if (!"SEND".equals(operation.kind()) || !operation.body().equals(originalDecision.draft().body())
                || !Objects.equals(version.publicationId(), operation.id())) return List.of("OPERATION_CONTRACT_MISMATCH");
        List<SourceMaterial> materials = Arrays.asList(json.readValue(version.materialsPayload(), SourceMaterial[].class));
        var policy = json.readValue(version.policyPayload(), EditorialPolicy.class);
        List<SourcePolicy> sources = mapper.lockSources().stream().filter(s -> s.synthetic() == operation.synthetic())
                .map(s -> new SourcePolicy(s.sourceId(), s.enabled(), s.approved(), s.synthetic(), s.licenseVersion(),
                        s.permittedProducers().isBlank() ? Set.of() : Set.copyOf(Arrays.asList(s.permittedProducers().split(",")))))
                .toList();
        var previousSources = Arrays.asList(json.readValue(version.sourcesPayload(), SourcePolicy[].class));
        if (previousSources.stream().anyMatch(previous -> sources.stream()
                .noneMatch(current -> previous.equals(current)))) return List.of("SOURCE_POLICY_CHANGED");
        return editorial.evaluate(materials, sources, policy).reasons();
    }
}
