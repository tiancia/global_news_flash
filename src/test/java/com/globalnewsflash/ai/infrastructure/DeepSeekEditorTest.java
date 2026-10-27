package com.globalnewsflash.ai.infrastructure;

import com.globalnewsflash.editorial.application.EditorFailure;
import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeepSeekEditorTest {
    private final JsonMapper json = new JsonMapper();
    private final DeepSeekProperties properties = configured();
    private final HttpClient client = mock(HttpClient.class);
    private final DeepSeekEditor editor = new DeepSeekEditor(properties, json, client);
    private final EditorRequest request = new EditorRequest("request", "event", "prompt", "contract",
            List.of(new EditorRequest.EvidenceFact("s", "m", "f", "An example supporting fact.", "https://example.org")),
            Instant.parse("2026-01-01T00:00:00Z"), false);

    private static DeepSeekProperties configured() {
        var p = new DeepSeekProperties();
        p.setApiKey("test-key"); p.setModel("deepseek-flash");
        p.setInputMicrosPerMillionTokens(280_000); p.setOutputMicrosPerMillionTokens(420_000);
        return p;
    }

    private byte[] response(String finishReason, boolean includeUsage, String content) {
        var root = new java.util.HashMap<String, Object>();
        root.put("model", "deepseek-flash");
        root.put("choices", List.of(Map.of("finish_reason", finishReason,
                "message", Map.of("role", "assistant", "content", content))));
        if (includeUsage) root.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 50));
        return json.writeValueAsBytes(root);
    }

    private static final String DRAFT = """
        {"risk":"LOW","relevant":true,"statements":[{"sourceId":"s","materialId":"m",
        "factId":"f","text":"An example supporting fact.","quote":"An example supporting fact."}]}
        """;

    @Test void parsesStructuredDraftAndActualUsage() {
        EditorResponse result = editor.parse(response("stop", true, DRAFT), request);
        assertThat(result.provider()).isEqualTo("deepseek");
        assertThat(result.statements()).hasSize(1);
        assertThat(result.usage()).isEqualTo(new EditorResponse.Usage(100, 50, false));
        assertThat(properties.actualMicros(result.usage())).isEqualTo(49);
        assertThat(properties.estimatedRequestMicros()).isGreaterThan(49);
    }

    @Test void incompleteAndMalformedOutputsRetainKnownUsage() {
        assertThatThrownBy(() -> editor.parse(response("length", true, DRAFT), request))
                .isInstanceOfSatisfying(EditorFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("DEEPSEEK_RESPONSE_INCOMPLETE");
                    assertThat(f.chargedUnknown()).isFalse();
                    assertThat(f.usage()).isEqualTo(new EditorResponse.Usage(100, 50, false));
                });
        assertThatThrownBy(() -> editor.parse(response("stop", true, DRAFT.replace("\"LOW\"", "\"unsafe\"")), request))
                .isInstanceOfSatisfying(EditorFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("DEEPSEEK_OUTPUT_INVALID");
                    assertThat(f.usage()).isNotNull();
                });
    }

    @Test void missingUsageIsUncertainAndCannotBePriced() {
        assertThatThrownBy(() -> editor.parse(response("stop", false, DRAFT), request))
                .isInstanceOfSatisfying(EditorFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("DEEPSEEK_USAGE_UNKNOWN");
                    assertThat(f.chargedUnknown()).isTrue();
                });
    }

    @Test void endpointRequiresHttpsAndConfiguredPrices() {
        assertThat(properties.endpoint().toString()).isEqualTo("https://api.deepseek.com/chat/completions");
        properties.setBaseUrl("http://api.deepseek.com");
        assertThat(properties.readinessReasons()).contains("DEEPSEEK_ENDPOINT_INVALID");
        properties.setBaseUrl("https://api.deepseek.com");
        properties.setInputMicrosPerMillionTokens(0);
        assertThat(properties.readinessReasons()).contains("DEEPSEEK_PRICES_NOT_CONFIGURED");
    }

    @SuppressWarnings("unchecked")
    @Test void sendsChatCompletionsRequestAndRejectsDefiniteAuthFailure() {
        HttpResponse<byte[]> reply = mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(401);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(reply));
        assertThatThrownBy(() -> editor.edit(request)).isInstanceOfSatisfying(EditorFailure.class, f -> {
            assertThat(f.code()).isEqualTo("DEEPSEEK_AUTH_REJECTED");
            assertThat(f.chargedUnknown()).isFalse();
        });
        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(capture.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(capture.getValue().uri().toString()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(capture.getValue().method()).isEqualTo("POST");
    }

    @SuppressWarnings("unchecked")
    @Test void successfulRequestUsesJsonContractAndAccountsUsage() throws Exception {
        HttpResponse<byte[]> reply = mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(200);
        when(reply.body()).thenReturn(response("stop", true, DRAFT));
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(reply));
        assertThat(editor.edit(request).provider()).isEqualTo("deepseek");
        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(capture.capture(), any(HttpResponse.BodyHandler.class));
        var result = new CompletableFuture<String>();
        capture.getValue().bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer b) { byte[] part = new byte[b.remaining()]; b.get(part); bytes.writeBytes(part); }
            @Override public void onError(Throwable t) { result.completeExceptionally(t); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        var payload = json.readTree(result.get(1, TimeUnit.SECONDS));
        assertThat(payload.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(payload.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(payload.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(payload.path("messages").get(0).path("content").asText())
                .contains("JSON", "untrusted", "combination at most once", "Every number",
                        "When there is only one supplied fact, return at most one statement in total.");
        assertThat(payload.path("messages").get(1).path("content").asText()).contains("An example supporting fact.");
    }

    @SuppressWarnings("unchecked")
    @Test void balanceRejectionIsDefiniteButTransportTimeoutIsUncertain() {
        HttpResponse<byte[]> reply = mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(402);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(reply))
                .thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException("private details")));
        assertThatThrownBy(() -> editor.edit(request)).isInstanceOfSatisfying(EditorFailure.class, f -> {
            assertThat(f.code()).isEqualTo("DEEPSEEK_BALANCE_INSUFFICIENT");
            assertThat(f.chargedUnknown()).isFalse();
        });
        assertThatThrownBy(() -> editor.edit(request)).isInstanceOfSatisfying(EditorFailure.class, f -> {
            assertThat(f.code()).isEqualTo("DEEPSEEK_TIMEOUT");
            assertThat(f.chargedUnknown()).isTrue();
            assertThat(f.getMessage()).doesNotContain("private details");
        });
    }

    @Test void rejectsSyntheticInputBeforeSending() {
        var synthetic = new EditorRequest("id", "event", "prompt", "contract", request.facts(), request.editedAt(), true);
        assertThatThrownBy(() -> editor.edit(synthetic)).isInstanceOfSatisfying(EditorFailure.class, f -> {
            assertThat(f.code()).isEqualTo("DEEPSEEK_INPUT_INVALID");
            assertThat(f.chargedUnknown()).isFalse();
        });
        verifyNoInteractions(client);
    }
}
