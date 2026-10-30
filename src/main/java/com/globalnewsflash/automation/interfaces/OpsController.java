package com.globalnewsflash.automation.interfaces;

import com.globalnewsflash.automation.domain.DemoScenario;
import com.globalnewsflash.automation.port.in.AutomationUseCase;
import com.globalnewsflash.automation.port.in.DemoUseCase;
import com.globalnewsflash.automation.port.in.LivePipelineUseCase;
import com.globalnewsflash.automation.port.in.NotificationUseCase;
import com.globalnewsflash.automation.port.in.PipelineUseCase;
import com.globalnewsflash.automation.port.out.OperatingSettings;
import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.digest.port.in.DigestUseCase;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ops")
public class OpsController {
    private final PipelineUseCase pipeline;
    private final PublicationUseCase publication;
    private final DemoUseCase demo;
    private final RuntimeSettings runtime;
    private final AutomationUseCase automation;
    private final LivePipelineUseCase livePipeline;
    private final OperatingSettings operating;
    private final NotificationUseCase notifications;
    private final DigestUseCase digest;

    public OpsController(PipelineUseCase pipeline, PublicationUseCase publication, DemoUseCase demo,
                         RuntimeSettings runtime,
                         AutomationUseCase automation, LivePipelineUseCase livePipeline, OperatingSettings operating,
                         NotificationUseCase notifications, DigestUseCase digest) {
        this.pipeline = pipeline;
        this.publication = publication;
        this.demo = demo;
        this.runtime = runtime;
        this.automation = automation;
        this.livePipeline = livePipeline;
        this.operating = operating;
        this.notifications = notifications;
        this.digest = digest;
    }
    public record PauseRequest(@NotNull Boolean paused) {}
    public record SourceRequest(@NotNull Boolean enabled) {}
    public record DemoRequest(@NotNull DemoScenario scenario) {}
    public record CostResolutionRequest(@NotNull @jakarta.validation.constraints.PositiveOrZero Long actualMicros,
                                        @NotBlank @Size(max = 1000) String note) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("mode", runtime.getMode(), "externalEnabled", runtime.isExternalEnabled(),
                "activeEditor", "LIVE".equals(runtime.getMode()) ? runtime.getPreferredProvider() : "synthetic",
                "preferredProvider", runtime.getPreferredProvider(), "paused", publication.isPaused(),
                "operatingPolicyReady", operating.problems().isEmpty(), "automation", automation.status());
    }
    @GetMapping("/sources") public Object sources() { return pipeline.sources(); }
    @GetMapping("/events") public Object events() { return pipeline.versions(); }

    @GetMapping("/automation") public Object automation() { return automation.status(); }
    @PostMapping("/run") public Object run() { return automation.runOnce(); }
    @GetMapping("/ingestion") public Object ingestion() { return automation.ingestionStatus(); }
    @GetMapping("/ingestion/inbox") public Object inbox() { return automation.inboxStatus(); }
    @GetMapping("/ingestion/failures") public Object articleFailures() { return automation.articleFailures(); }
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

    @PostMapping("/recover") public Object recover() {
        return Map.of("recovered", publication.recoverExpired(), "editorJobsRecovered", livePipeline.recoverExpired());
    }

}
