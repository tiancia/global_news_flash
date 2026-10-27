package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.domain.DemoScenario;
import com.globalnewsflash.automation.port.in.BudgetUseCase;
import com.globalnewsflash.automation.port.in.DemoUseCase;
import com.globalnewsflash.automation.port.in.PipelineUseCase;
import com.globalnewsflash.automation.port.out.DemoFixtures;
import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.growth.port.in.GrowthUseCase;
import com.globalnewsflash.ingestion.port.out.SourceAdapter;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Explicit fictional scenarios. The values here are test inputs, not operating policy. */
@Service
public class DemoService implements DemoUseCase {
    private final PipelineUseCase pipeline;
    private final DemoFixtures fixtures;
    private final PublicationUseCase publication;
    private final List<SourceAdapter> adapters;
    private final GrowthUseCase growth;
    private final BudgetUseCase budgets;
    private final Clock clock;
    private final RuntimeSettings runtime;

    public DemoService(PipelineUseCase pipeline, PublicationUseCase publication, List<SourceAdapter> adapters,
                       GrowthUseCase growth, BudgetUseCase budgets, Clock clock, RuntimeSettings runtime, DemoFixtures fixtures) {
        this.pipeline = pipeline; this.publication = publication; this.adapters = List.copyOf(adapters);
        this.growth = growth; this.budgets = budgets; this.clock = clock;
        this.runtime = runtime;
        this.fixtures = fixtures;
    }

    public DemoResult run(DemoScenario scenario) {
        if (!"OFFLINE".equals(runtime.getMode())) throw new IllegalStateException("Demo requires OFFLINE mode");
        var materials = scenario.materials(adapters.stream().filter(a -> a.sourceId().equals(scenario.source())).findFirst().orElseThrow()
                .parse(fixtures.load(scenario.fixture()), clock.instant(), true));
        var policy = scenario.policy();
        budgets.configureSyntheticPeriod("demo-explicit-period", 1000, 20);
        var run = pipeline.process(materials, policy, new PipelineUseCase.BudgetAllocation("demo-explicit-period", 100));
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


}
