package com.globalnewsflash.integrations.telegram;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.publication.domain.*;
import com.globalnewsflash.publication.port.out.PublicationRepository;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings("unchecked")
class TelegramDeliveryGatewayTest {
    static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");
    HttpClient client;
    TelegramDeliveryGateway gateway;

    @BeforeEach void setup() {
        client = mock(HttpClient.class);
        var runtime = new RuntimeProperties(); runtime.setMode("LIVE"); runtime.setExternalEnabled(true);
        var properties = new TelegramProperties(); properties.setEnabled(true);
        properties.setBotToken("123456:" + "x".repeat(30)); properties.setChatId("-1001234567890");
        gateway = new TelegramDeliveryGateway(runtime, properties, mock(PublicationRepository.class),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC), client);
    }

    @Test void preflightTimeoutIsRetryableAndDoesNotSend() {
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException("private details")));
        var result = gateway.deliver(operation());
        assertThat(result.outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE);
        assertThat(result.error()).isEqualTo("TELEGRAM_PREFLIGHT_UNAVAILABLE");
        verify(client, times(1)).sendAsync(argThat(r -> r.uri().getPath().endsWith("/getMe")), any(HttpResponse.BodyHandler.class));
    }

    @ParameterizedTest @ValueSource(ints = {500, 503})
    void preflightServerErrorIsRetryable(int status) {
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(reply(status, "{\"ok\":false,\"error_code\":" + status + "}"));
        assertThat(gateway.deliver(operation()).outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE);
    }

    @Test void preflightRateLimitPreservesRetryAfter() {
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(reply(429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":120}}"));
        var result = gateway.deliver(operation());
        assertThat(result.outcome()).isEqualTo(DeliveryResult.Outcome.RATE_LIMITED);
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(120));
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403})
    void definitePreflightRejectionIsNotRetried(int status) {
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(reply(status, "{\"ok\":false,\"error_code\":" + status + "}"));
        assertThat(gateway.deliver(operation()).outcome()).isEqualTo(DeliveryResult.Outcome.FAILED);
    }

    @Test void timeoutAfterSendRemainsUnknown() {
        successfulPreflight().thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException("timeout")));
        assertThat(gateway.deliver(operation()).outcome()).isEqualTo(DeliveryResult.Outcome.UNKNOWN);
        verify(client, times(4)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void confirmedSendReturnsMessageId() {
        successfulPreflight().thenReturn(reply(200, "{\"ok\":true,\"result\":{\"message_id\":42,\"chat\":{\"id\":-1001234567890}}}"));
        assertThat(gateway.deliver(operation())).isEqualTo(DeliveryResult.success("42"));
    }

    private org.mockito.stubbing.OngoingStubbing<CompletableFuture<HttpResponse<byte[]>>> successfulPreflight() {
        return when(client.<byte[]>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(reply(200, "{\"ok\":true,\"result\":{\"id\":123456,\"is_bot\":true}}"))
                .thenReturn(reply(200, "{\"ok\":true,\"result\":{\"id\":-1001234567890,\"type\":\"channel\"}}"))
                .thenReturn(reply(200, "{\"ok\":true,\"result\":{\"status\":\"administrator\",\"can_post_messages\":true,\"user\":{\"id\":123456}}}"));
    }
    private CompletableFuture<HttpResponse<byte[]>> reply(int status, String body) {
        HttpResponse<byte[]> response = new HttpResponse<>() {
            public int statusCode() { return status; }
            public byte[] body() { return body.getBytes(StandardCharsets.UTF_8); }
            public HttpRequest request() { return null; }
            public java.util.Optional<HttpResponse<byte[]>> previousResponse() { return java.util.Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public java.net.URI uri() { return java.net.URI.create("https://api.telegram.org"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        return CompletableFuture.completedFuture(response);
    }
    private PublicationOperation operation() {
        return new PublicationOperation("id", "event", 1, "-1001234567890", "SEND", "SENDING", "Test news body",
                null, null, 1, "lease", NOW.plusSeconds(120), null, null, false);
    }
}
