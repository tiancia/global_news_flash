package com.globalnewsflash.automation.application;

import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.growth.application.GrowthService;
import com.globalnewsflash.ingestion.application.SourceAdapter;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.publication.application.PublicationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Explicit fictional scenarios. The values here are test inputs, not operating policy. */
@Service
public class DemoService {
    public enum Scenario { VALID, REVISED, STALE, DISCOVERY, TITLE_ONLY }
    public record DemoResult(String mode, Scenario scenario, PipelineService.PipelineRun pipeline,
                             String deliveryState, String messageId) {}
    private final PipelineService pipeline;
    private final PublicationService publication;
    private final List<SourceAdapter> adapters;
    private final GrowthService growth;
    private final BudgetService budgets;
    private final Clock clock;

    public DemoService(PipelineService pipeline, PublicationService publication, List<SourceAdapter> adapters,
                       GrowthService growth, BudgetService budgets, Clock clock) {
        this.pipeline = pipeline; this.publication = publication; this.adapters = List.copyOf(adapters);
        this.growth = growth; this.budgets = budgets; this.clock = clock;
    }

    public DemoResult run(Scenario scenario) {
        String source = switch (scenario) { case DISCOVERY -> "gdelt"; case TITLE_ONLY -> "fed"; default -> "usgs"; };
        String file = switch (scenario) {
            case REVISED -> "usgs-revised.json";
            case DISCOVERY -> "gdelt.json";
            case TITLE_ONLY -> "fed.xml";
            default -> "usgs.json";
        };
        List<SourceMaterial> materials = adapters.stream().filter(a -> a.sourceId().equals(source)).findFirst().orElseThrow()
                .parse(fixture(file), clock.instant(), true);
        // The stale scenario has its own event identity so it cannot invalidate the valid demo.
        if (scenario == Scenario.STALE) {
            materials = materials.stream().map(m -> new SourceMaterial(m.sourceId(), m.externalId(),
                    m.eventKey() + ":stale", m.canonicalUrl(), m.headline(), m.topic(), m.producer(),
                    m.evidenceChainId(), m.occurredAt(), m.publishedAt(), m.sourceUpdatedAt(),
                    m.discoveredAt(), m.facts(), m.discoveryOnly(), true)).toList();
        }
        var policy = new EditorialPolicy("demo-" + scenario.name().toLowerCase() + "-v1",
                scenario == Scenario.STALE ? Duration.ofSeconds(1) : Duration.ofHours(48),
                2000, 1, Set.of("EARTHQUAKE", "OFFICIAL_ANNOUNCEMENT"), true);
        // Revision keeps the original policy identity: this is a changed source version.
        if (scenario == Scenario.REVISED) policy = new EditorialPolicy("demo-valid-v1", Duration.ofHours(48),
                2000, 1, Set.of("EARTHQUAKE", "OFFICIAL_ANNOUNCEMENT"), true);
        budgets.configureSyntheticPeriod("demo-explicit-period", 1000, 20);
        var run = pipeline.process(materials, policy, new PipelineService.BudgetAllocation("demo-explicit-period", 100));
        String state = null;
        String messageId = null;
        if (run.publicationId() != null) {
            // Web demos honor the global pause; they never change the operator's switch.
            var operation = publication.dispatch(run.publicationId());
            state = operation.state(); messageId = operation.messageId();
            if ("SENT".equals(state)) demonstrateGrowth(operation.body());
        }
        return new DemoResult("SYNTHETIC_OFFLINE", scenario, run, state, messageId);
    }

    private void demonstrateGrowth(String validatedBody) {
        var materials = growth.createMaterials(List.of(validatedBody));
        growth.authorizeSyntheticTarget("demo-owned-channel", "Fictional owned channel");
        growth.distribute("demo-daily-share-v1", "demo-owned-channel", materials.promotion());
        growth.registerSyntheticInvite("demo-owned-channel", "demo-invite-key");
        growth.recordJoin(1001L, "demo-invite-key");
        growth.recordJoin(1002L, null);
    }

    private static String fixture(String file) {
        try (var stream = new ClassPathResource("fixtures/" + file).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Local fixture unavailable", failure); }
    }
}
