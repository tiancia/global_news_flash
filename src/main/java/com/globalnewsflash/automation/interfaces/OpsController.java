package com.globalnewsflash.automation.interfaces;

import com.globalnewsflash.automation.application.BudgetService;
import com.globalnewsflash.automation.application.DemoService;
import com.globalnewsflash.automation.application.PipelineService;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.growth.application.GrowthService;
import com.globalnewsflash.publication.application.PublicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ops")
public class OpsController {
    private final PipelineService pipeline;
    private final PublicationService publication;
    private final DemoService demo;
    private final GrowthService growth;
    private final BudgetService budgets;
    private final RuntimeProperties runtime;
    private final Clock clock;

    public OpsController(PipelineService pipeline, PublicationService publication, DemoService demo,
                         GrowthService growth, BudgetService budgets, RuntimeProperties runtime, Clock clock) {
        this.pipeline = pipeline; this.publication = publication; this.demo = demo; this.growth = growth;
        this.budgets = budgets; this.runtime = runtime; this.clock = clock;
    }
    public record PauseRequest(@NotNull Boolean paused) {}
    public record SourceRequest(@NotNull Boolean enabled) {}
    public record DemoRequest(@NotNull DemoService.Scenario scenario) {}
    public record ResolutionRequest(@NotBlank String resolution, @Size(max = 128) String messageId,
                                    @NotBlank @Size(max = 1000) String note) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("mode", runtime.getMode(), "externalEnabled", false, "activeEditor", "synthetic",
                "preferredProvider", runtime.getPreferredProvider(), "paused", publication.isPaused(),
                "operatingPolicyReady", false);
    }
    @GetMapping("/sources") public Object sources() { return pipeline.sources(); }
    @GetMapping("/events") public Object events() { return pipeline.versions(); }
    @GetMapping("/publications") public Object publications() { return publication.list(); }
    @GetMapping("/publications/{id}/attempts") public Object attempts(@PathVariable String id) { return publication.attempts(id); }
    @GetMapping("/alerts") public Object alerts() { return publication.alerts(); }
    @GetMapping("/publications/{id}/resolutions") public Object resolutions(@PathVariable String id) { return publication.resolutions(id); }

    @PostMapping("/pause")
    public Object pause(@Valid @RequestBody PauseRequest request) {
        publication.setPaused(request.paused()); return status();
    }
    @PostMapping("/sources/{id}/state")
    public Object source(@PathVariable String id, @Valid @RequestBody SourceRequest request) {
        pipeline.setSourceEnabled(id, request.enabled()); return pipeline.sources();
    }
    @PostMapping("/demo")
    public Object demo(@Valid @RequestBody DemoRequest request) { return demo.run(request.scenario()); }
    @PostMapping("/publications/{id}/dispatch") public Object dispatch(@PathVariable String id) { return publication.dispatch(id); }
    @PostMapping("/recover") public Object recover() { return Map.of("recovered", publication.recoverExpired()); }
    @PostMapping("/publications/{id}/resolve")
    public Object resolve(@PathVariable String id, @Valid @RequestBody ResolutionRequest request) {
        return publication.resolveUnknown(id, request.resolution(), request.messageId(), request.note());
    }

    @GetMapping("/report")
    public Map<String, Object> report() {
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", "SYNTHETIC_OFFLINE"); result.put("generatedAt", clock.instant());
        result.put("materials", pipeline.materialCount()); result.put("auditRecords", pipeline.auditCount());
        result.put("editorialStates", pipeline.versions().stream()
                .collect(Collectors.groupingBy(v -> v.editorialState(), Collectors.counting())));
        result.put("deliveryStates", publication.list().stream()
                .collect(Collectors.groupingBy(o -> o.state(), Collectors.counting())));
        result.put("growth", growth.report()); result.put("budgetPeriods", budgets.report());
        result.put("localAlerts", publication.alerts());
        result.put("subscriberChange", "UNKNOWN"); result.put("reads", "UNKNOWN");
        result.put("realModelUsage", "NOT_CALLED"); result.put("realCosts", "NOT_INCURRED");
        return result;
    }
}
