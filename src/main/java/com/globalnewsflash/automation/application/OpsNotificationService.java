package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.infrastructure.AlertProperties;
import com.globalnewsflash.automation.infrastructure.LiveAutomationMapper;
import com.globalnewsflash.automation.infrastructure.NotificationMapper;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.publication.application.PublicationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Optional, at-least-once webhook alerts containing stable codes and identifiers only. */
@Service
public class OpsNotificationService {
    private final RuntimeProperties runtime;
    private final AlertProperties properties;
    private final LiveAutomationMapper automation;
    private final PublicationService publications;
    private final NotificationMapper notices;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public OpsNotificationService(RuntimeProperties runtime, AlertProperties properties, LiveAutomationMapper automation,
            PublicationService publications, NotificationMapper notices, JsonMapper json, Clock clock) {
        this.runtime = runtime; this.properties = properties; this.automation = automation;
        this.publications = publications; this.notices = notices; this.json = json; this.clock = clock;
    }

    public int flush() {
        if (!runtime.live() || !properties.isEnabled() || !properties.configured()) return 0;
        for (var alert : automation.unnotifiedAlerts()) add(alert.id(), alert.category(), alert.subjectId(), alert.reasonCode());
        for (var alert : publications.unnotifiedAlerts()) {
            if (alert.acknowledgedAt() == null) add(alert.id(), alert.category(), alert.operationId(), alert.reasonCode());
        }
        int delivered = 0;
        for (var notice : notices.due(clock.millis())) {
            if (notice.attempts() >= 5) {
                notices.finish(notice.id(), notice.attempts(), "FAILED", clock.millis(), "ALERT_ATTEMPTS_EXHAUSTED");
                continue;
            }
            if (notices.claim(notice.id(), clock.millis() + properties.getTimeout().toMillis() + 10_000, clock.millis()) != 1) continue;
            boolean success = false;
            java.util.concurrent.CompletableFuture<HttpResponse<Void>> request = null;
            try {
                var builder = HttpRequest.newBuilder(URI.create(properties.getWebhookUrl())).timeout(properties.getTimeout())
                        .header("Content-Type", "application/json");
                if (!properties.getWebhookToken().isBlank()) builder.header("Authorization", "Bearer " + properties.getWebhookToken());
                var body = Map.of("id", notice.id(), "service", "global-news-flash", "category", notice.category(),
                        "subjectId", notice.subjectId(), "reasonCode", notice.reasonCode());
                request = client.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                        HttpResponse.BodyHandlers.discarding());
                int status = request.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS).statusCode();
                success = status >= 200 && status < 300;
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception unavailable) { /* Stable state below; never retain webhook URL or exception details. */ }
            finally { if (request != null && !request.isDone()) request.cancel(true); }
            int attempts = notice.attempts() + 1;
            notices.finish(notice.id(), attempts, success ? "SENT" : attempts >= 5 ? "FAILED" : "PENDING",
                    clock.millis() + Math.min(3_600_000L, 30_000L << Math.min(attempts, 6)),
                    success ? null : "ALERT_WEBHOOK_UNAVAILABLE");
            if (success) delivered++;
        }
        return delivered;
    }
    public Object status() { return Map.of("enabled", properties.isEnabled(), "configured", properties.configured(), "notifications", notices.list()); }
    private void add(String id, String category, String subject, String reason) {
        try { notices.insert(new NotificationMapper.Notice(id, category, subject, reason, "PENDING", 0, 0, null)); }
        catch (DuplicateKeyException exists) { /* a persisted alert is notified at most once after success */ }
    }
}
