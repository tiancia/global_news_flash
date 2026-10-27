package com.globalnewsflash.integrations.webhook;

import com.globalnewsflash.automation.port.out.NotificationGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** One webhook POST with sanitized failure handling; never retries inside the transport. */
@Component
public final class WebhookNotificationGateway implements NotificationGateway {
    private final AlertProperties properties;
    private final JsonMapper json;
    private final HttpClient client;

    @Autowired
    public WebhookNotificationGateway(AlertProperties properties, JsonMapper json) {
        this(properties, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WebhookNotificationGateway(AlertProperties properties, JsonMapper json, HttpClient client) {
        this.properties = properties;
        this.json = json;
        this.client = client;
    }

    @Override
    public boolean deliver(Notification notification) {
        if (!properties.isEnabled() || !properties.configured()) return false;
        CompletableFuture<HttpResponse<Void>> request = null;
        try {
            var builder = HttpRequest.newBuilder(URI.create(properties.getWebhookUrl())).timeout(properties.getTimeout())
                    .header("Content-Type", "application/json");
            if (!properties.getWebhookToken().isBlank()) builder.header("Authorization", "Bearer " + properties.getWebhookToken());
            var body = Map.of("id", notification.id(), "service", "global-news-flash", "category", notification.category(),
                    "subjectId", notification.subjectId(), "reasonCode", notification.reasonCode());
            request = client.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.discarding());
            int status = request.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS).statusCode();
            return status >= 200 && status < 300;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception unavailable) {
            // Never retain webhook URLs, tokens, response bodies or exception details in operational state.
            return false;
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
        }
    }
}
