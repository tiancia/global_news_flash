package com.globalnewsflash.automation.interfaces;

import com.globalnewsflash.automation.application.BudgetService;
import com.globalnewsflash.automation.application.DemoService;
import com.globalnewsflash.automation.application.PipelineService;
import com.globalnewsflash.automation.application.LiveAutomationService;
import com.globalnewsflash.automation.application.LivePipelineService;
import com.globalnewsflash.automation.application.OpsNotificationService;
import com.globalnewsflash.automation.infrastructure.OperatingProperties;
import com.globalnewsflash.digest.application.DigestService;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.growth.application.GrowthService;
import com.globalnewsflash.publication.application.PublicationService;
import com.globalnewsflash.publication.infrastructure.TelegramMessageText;
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
    private final LiveAutomationService automation;
    private final LivePipelineService livePipeline;
    private final OperatingProperties operating;
    private final OpsNotificationService notifications;
    private final DigestService digest;

    public OpsController(PipelineService pipeline, PublicationService publication, DemoService demo,
                         GrowthService growth, BudgetService budgets, RuntimeProperties runtime, Clock clock,
                         LiveAutomationService automation, LivePipelineService livePipeline, OperatingProperties operating,
                         OpsNotificationService notifications, DigestService digest) {
        this.pipeline = pipeline; this.publication = publication; this.demo = demo; this.growth = growth;
        this.budgets = budgets; this.runtime = runtime; this.clock = clock;
        this.automation = automation; this.livePipeline = livePipeline; this.operating = operating;
        this.notifications = notifications;
        this.digest = digest;
    }
    public record PauseRequest(@NotNull Boolean paused) {}
    public record SourceRequest(@NotNull Boolean enabled) {}
    public record DemoRequest(@NotNull DemoService.Scenario scenario) {}
    public record ResolutionRequest(@NotBlank String resolution, @Size(max = 128) String messageId,
                                    @NotBlank @Size(max = 1000) String note) {}
    public record CostResolutionRequest(@NotNull @jakarta.validation.constraints.PositiveOrZero Long actualMicros,
                                        @NotBlank @Size(max = 1000) String note) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("mode", runtime.getMode(), "externalEnabled", runtime.isExternalEnabled(),
                "activeEditor", "LIVE".equals(runtime.getMode()) ? "openai" : "synthetic",
                "preferredProvider", runtime.getPreferredProvider(), "paused", publication.isPaused(),
                "operatingPolicyReady", operating.problems().isEmpty(), "automation", automation.status());
    }
    @GetMapping("/sources") public Object sources() { return pipeline.sources(); }
    @GetMapping("/events") public Object events() { return pipeline.versions(); }
    @GetMapping("/publications") public Object publications() { return publication.list(); }
    @GetMapping("/publications/{id}/preview")
    public Object preview(@PathVariable String id) {
        var operation = publication.get(id);
        return Map.of("operation", operation, "telegramText", TelegramMessageText.render(operation));
    }
    @GetMapping("/publications/{id}/attempts") public Object attempts(@PathVariable String id) { return publication.attempts(id); }
    @GetMapping("/alerts") public Object alerts() { return publication.alerts(); }
    @GetMapping("/publications/{id}/resolutions") public Object resolutions(@PathVariable String id) { return publication.resolutions(id); }
    @GetMapping("/automation") public Object automation() { return automation.status(); }
    @PostMapping("/run") public Object run() { return automation.runOnce(); }
    @GetMapping("/ingestion") public Object ingestion() { return automation.ingestionStatus(); }
    @GetMapping("/ingestion/inbox") public Object inbox() { return automation.inboxStatus(); }
    @PostMapping("/ingestion/inbox/{id}/retry") public Object retryInbox(@PathVariable String id) { return automation.requeue(id); }
    @PostMapping("/ingestion/poll") public Object poll() { return automation.poll(); }
    @GetMapping("/editor-jobs") public Object editorJobs() { return automation.jobs(); }
    @GetMapping("/automation/alerts") public Object automationAlerts() { return automation.alerts(); }
    @GetMapping("/notifications") public Object notifications() { return notifications.status(); }
    @PostMapping("/notifications/flush") public Object flushNotifications() { return Map.of("delivered", notifications.flush()); }
    @GetMapping("/digests") public Object digests() { return digest.list(); }
    @PostMapping("/digests/run") public Object runDigest() { return digest.runDue(); }
    @PostMapping("/editor-jobs/{id}/resolve-cost")
    public Object resolveCost(@PathVariable String id, @Valid @RequestBody CostResolutionRequest request) {
        return livePipeline.resolveCost(id, request.actualMicros(), request.note());
    }
    @PostMapping("/editor-jobs/{id}/retry") public Object retryEditor(@PathVariable String id) { return livePipeline.retryJob(id); }

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
    @PostMapping("/recover") public Object recover() {
        return Map.of("recovered", publication.recoverExpired(), "editorJobsRecovered", livePipeline.recoverExpired());
    }
    @PostMapping("/publications/{id}/resolve")
    public Object resolve(@PathVariable String id, @Valid @RequestBody ResolutionRequest request) {
        return publication.resolveUnknown(id, request.resolution(), request.messageId(), request.note());
    }

    @GetMapping("/report")
    public Map<String, Object> report() {
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", runtime.live() ? "LIVE" : runtime.getMode().equals("OFFLINE") ? "SYNTHETIC_OFFLINE" : "LIVE_DISABLED");
        result.put("generatedAt", clock.instant());
        result.put("materials", pipeline.materialCount()); result.put("auditRecords", pipeline.auditCount());
        result.put("editorialStates", pipeline.versions().stream()
                .collect(Collectors.groupingBy(v -> v.editorialState(), Collectors.counting())));
        result.put("deliveryStates", publication.list().stream()
                .collect(Collectors.groupingBy(o -> o.state(), Collectors.counting())));
        result.put("growth", growth.report()); result.put("budgetPeriods", budgets.report());
        result.put("localAlerts", publication.alerts());
        result.put("automationAlerts", automation.alerts());
        result.put("subscriberChange", "UNKNOWN"); result.put("reads", "UNKNOWN");
        var livePeriods = budgets.report().stream().filter(p -> p.id().startsWith("live-usd:")).toList();
        result.put("realModelUsage", livePeriods.isEmpty() ? "NOT_CALLED" : Map.of(
                "reservedRequests", livePeriods.stream().mapToLong(p -> p.usedRequests()).sum()));
        result.put("realCosts", livePeriods.isEmpty() ? "NOT_INCURRED" : Map.of("currency", "USD",
                "spentMicros", livePeriods.stream().mapToLong(p -> p.spentMicros()).sum(),
                "reservedOrUncertainMicros", livePeriods.stream().mapToLong(p -> p.reservedMicros()).sum()));
        return result;
    }
}
