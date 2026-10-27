package com.globalnewsflash.integrations.webhook;

import com.globalnewsflash.automation.application.NotificationGateway.Notification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookNotificationGatewayTest {
    private final AlertProperties properties = configured();
    private final JsonMapper json = new JsonMapper();
    private final HttpClient client = mock(HttpClient.class);
    private final WebhookNotificationGateway gateway = new WebhookNotificationGateway(properties, json, client);
    private final Notification notification = new Notification("alert-1", "EDITOR", "job-1", "EDITOR_FAILED");

    private static AlertProperties configured() {
        var properties = new AlertProperties();
        properties.setEnabled(true);
        properties.setWebhookUrl("https://alerts.example.org/receive");
        properties.setWebhookToken("test-token");
        return properties;
    }

    @Test void postsExistingPayloadWithOptionalBearerAndConfiguredTimeout() throws Exception {
        respond(204);
        assertThat(gateway.deliver(notification)).isTrue();
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(captured.capture(), any(HttpResponse.BodyHandler.class));
        var request = captured.getValue();
        assertThat(request.uri().toString()).isEqualTo(properties.getWebhookUrl());
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.timeout()).contains(properties.getTimeout());
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer test-token");
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(json.readTree(body(request))).isEqualTo(json.valueToTree(Map.of(
                "id", "alert-1", "service", "global-news-flash", "category", "EDITOR",
                "subjectId", "job-1", "reasonCode", "EDITOR_FAILED")));
    }

    @Test void blankTokenOmitsAuthorizationHeader() {
        properties.setWebhookToken("");
        respond(200);
        assertThat(gateway.deliver(notification)).isTrue();
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(captured.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captured.getValue().headers().firstValue("Authorization")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 299, 302, 400, 401, 429, 500})
    void onlySuccessfulHttpStatusConfirmsDeliveryAndNeverRetries(int status) {
        respond(status);
        assertThat(gateway.deliver(notification)).isEqualTo(status >= 200 && status < 300);
        verify(client, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void disabledOrInvalidConfigurationDoesNotSend() {
        properties.setEnabled(false);
        assertThat(gateway.deliver(notification)).isFalse();
        properties.setEnabled(true);
        properties.setWebhookUrl("http://alerts.example.org/receive");
        assertThat(gateway.deliver(notification)).isFalse();
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    @Test void transportExceptionBecomesUnconfirmedDeliveryWithoutLeakingDetails() {
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("private URL and credential")));
        assertThat(gateway.deliver(notification)).isFalse();
        verify(client, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    @Test void timeoutCancelsPendingRequestWithoutRetry() {
        properties.setTimeout(Duration.ofMillis(1));
        var pending = new CompletableFuture<HttpResponse<Void>>();
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        assertThat(gateway.deliver(notification)).isFalse();
        assertThat(pending.isCancelled()).isTrue();
        verify(client, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    @Test void interruptionIsPreservedAndCancelsPendingRequest() {
        var pending = new CompletableFuture<HttpResponse<Void>>();
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        Thread.currentThread().interrupt();
        try {
            assertThat(gateway.deliver(notification)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending.isCancelled()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private void respond(int status) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(1, TimeUnit.SECONDS);
    }
}
