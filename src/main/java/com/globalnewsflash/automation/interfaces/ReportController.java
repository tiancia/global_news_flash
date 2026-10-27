package com.globalnewsflash.automation.interfaces;

import com.globalnewsflash.automation.application.BudgetService;
import com.globalnewsflash.automation.application.PipelineService;
import com.globalnewsflash.automation.application.LiveAutomationService;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.growth.application.GrowthService;
import com.globalnewsflash.publication.application.PublicationService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ops")
public class ReportController {
    private final PipelineService pipeline;
    private final PublicationService publication;
    private final GrowthService growth;
    private final BudgetService budgets;
    private final RuntimeProperties runtime;
    private final LiveAutomationService automation;
    private final Clock clock;

    public ReportController(PipelineService pipeline, PublicationService publication, GrowthService growth,
                            BudgetService budgets, RuntimeProperties runtime, LiveAutomationService automation, Clock clock) {
        this.pipeline = pipeline;
        this.publication = publication;
        this.growth = growth;
        this.budgets = budgets;
        this.runtime = runtime;
        this.automation = automation;
        this.clock = clock;
    }

    @GetMapping("/report")
    public Map<String, Object> report() {
        var budgetPeriods = budgets.report();
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", runtime.live() ? "LIVE" : runtime.getMode().equals("OFFLINE") ? "SYNTHETIC_OFFLINE" : "LIVE_DISABLED");
        result.put("generatedAt", clock.instant());
        result.put("materials", pipeline.materialCount());
        result.put("auditRecords", pipeline.auditCount());
        result.put("editorialStates", pipeline.versions().stream()
                .collect(Collectors.groupingBy(v -> v.editorialState(), Collectors.counting())));
        result.put("deliveryStates", publication.list().stream()
                .collect(Collectors.groupingBy(o -> o.state(), Collectors.counting())));
        result.put("growth", growth.report());
        result.put("budgetPeriods", budgetPeriods);
        result.put("localAlerts", publication.alerts());
        result.put("automationAlerts", automation.alerts());
        result.put("subscriberChange", "UNKNOWN");
        result.put("reads", "UNKNOWN");
        var livePeriods = budgetPeriods.stream().filter(p -> p.id().startsWith("live-usd:")).toList();
        result.put("realModelUsage", livePeriods.isEmpty() ? "NOT_CALLED" : Map.of(
                "reservedRequests", livePeriods.stream().mapToLong(p -> p.usedRequests()).sum()));
        result.put("realCosts", livePeriods.isEmpty() ? "NOT_INCURRED" : Map.of("currency", "USD",
                "spentMicros", livePeriods.stream().mapToLong(p -> p.spentMicros()).sum(),
                "reservedOrUncertainMicros", livePeriods.stream().mapToLong(p -> p.reservedMicros()).sum()));
        return result;
    }
}
