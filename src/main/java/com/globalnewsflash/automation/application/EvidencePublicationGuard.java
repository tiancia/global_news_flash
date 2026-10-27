package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.domain.PublicationEvidence;
import com.globalnewsflash.automation.port.in.BudgetUseCase;
import com.globalnewsflash.automation.port.out.PipelineRepository;
import com.globalnewsflash.digest.port.in.DigestGuard;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.editorial.port.in.EditorialUseCase;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceRegistration;
import com.globalnewsflash.ingestion.port.out.SourceRegistry;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.port.out.PublicationGuard;
import com.globalnewsflash.shared.port.out.SnapshotCodec;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails closed on orphaned tasks, withdrawn sources, expiry and superseded evidence. */
@Component
@ConditionalOnProperty(name = "news.evidence-guard.enabled", havingValue = "true", matchIfMissing = true)
public class EvidencePublicationGuard implements PublicationGuard {
    private final SourceRegistry sourceRegistry;
    private final PipelineRepository mapper;
    private final EditorialUseCase editorial;
    private final SnapshotCodec json;
    private final BudgetUseCase budgets;
    private final DigestGuard digest;

    public EvidencePublicationGuard(PipelineRepository mapper, EditorialUseCase editorial, SnapshotCodec json, BudgetUseCase budgets,
                                    DigestGuard digest, SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
        this.mapper = mapper; this.editorial = editorial; this.json = json; this.budgets = budgets;
        this.digest = digest;
    }

    @Override
    public List<String> rejectionReasons(PublicationOperation operation) {
        if (digest.handles(operation)) return digest.rejectionReasons(operation);
        var version = mapper.findNumber(operation.eventKey(), operation.eventVersion());
        if (!PublicationEvidence.eligible(version)) return List.of("NO_ELIGIBLE_EVIDENCE");
        var reservation = version.budgetReservationId() == null ? null : budgets.get(version.budgetReservationId());
        if (!PublicationEvidence.accounted(reservation)) return List.of("NO_ACCOUNTED_EDITOR_REQUEST");
        if (!PublicationEvidence.current(mapper.lockEvent(operation.eventKey()), operation.eventVersion())) {
            return List.of("EVENT_VERSION_SUPERSEDED");
        }
        var originalDecision = json.readValue(version.decisionPayload(), EditorialResult.class);
        // Every body is immutable and must match its validated draft. Live amendments additionally
        // require an original confirmed message and the explicit operating revision mode.
        if (!PublicationEvidence.matches(operation, version, originalDecision)) return List.of("OPERATION_CONTRACT_MISMATCH");
        List<SourceMaterial> materials = Arrays.asList(json.readValue(version.materialsPayload(), SourceMaterial[].class));
        var policy = json.readValue(version.policyPayload(), EditorialPolicy.class);
        List<SourcePolicy> sources = sourceRegistry.lockSources().stream().filter(s -> s.synthetic() == operation.synthetic())
                .map(SourceRegistration::policy).toList();
        var previousSources = Arrays.asList(json.readValue(version.sourcesPayload(), SourcePolicy[].class));
        if (!PublicationEvidence.unchangedSources(previousSources, sources)) return List.of("SOURCE_POLICY_CHANGED");
        return editorial.validateStoredDraft(materials, sources, policy, originalDecision.draft());
    }
}
