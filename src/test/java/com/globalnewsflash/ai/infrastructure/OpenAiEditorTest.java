package com.globalnewsflash.ai.infrastructure;

import com.globalnewsflash.editorial.application.EditorFailure;
import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiEditorTest {
    private final JsonMapper json = new JsonMapper();
    private final HttpClient client = mock(HttpClient.class);
    private final OpenAiProperties properties = new OpenAiProperties();
    private final EditorRequest request = new EditorRequest("id", "event", "prompt", "contract",
            List.of(new EditorRequest.EvidenceFact("s", "m", "f", "A supported fact.", "https://example.org")),
            Instant.parse("2026-01-01T00:00:00Z"), false);
    private static final String DRAFT = """
            {"risk":"LOW","relevant":true,"statements":[{"sourceId":"s","materialId":"m","factId":"f",
            "text":"A supported fact.","quote":"A supported fact."}]}
            """;

    private OpenAiEditor editor() {
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setInputMicrosPerMillionTokens(100_000);
        properties.setOutputMicrosPerMillionTokens(500_000);
        return new OpenAiEditor(properties, json, client);
    }

    @SuppressWarnings("unchecked")
    private void reply(Map<String, Object> payload) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(json.writeValueAsBytes(payload));
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private Map<String, Object> response(String draft) {
        return Map.of("status", "completed", "model", "test-model",
                "usage", Map.of("input_tokens", 100, "output_tokens", 50),
                "output", List.of(Map.of("type", "message", "role", "assistant", "status", "completed",
                        "content", List.of(Map.of("type", "output_text", "text", draft)))));
    }

    @Test void parsesResponsesEnvelopeUsingSharedDraftContract() {
        reply(response(DRAFT));
        var result = editor().edit(request);
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.usage()).isEqualTo(new EditorResponse.Usage(100, 50, false));
        assertThat(result.statements().getFirst().quote()).isEqualTo("A supported fact.");
    }

    @Test void invalidDraftRetainsKnownUsage() {
        reply(response(DRAFT.replace("\"LOW\"", "\"invalid\"")));
        assertThatThrownBy(() -> editor().edit(request)).isInstanceOfSatisfying(EditorFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo("OPENAI_OUTPUT_INVALID");
            assertThat(failure.chargedUnknown()).isFalse();
            assertThat(failure.usage()).isEqualTo(new EditorResponse.Usage(100, 50, false));
        });
    }

    @Test void missingUsageRemainsUncertain() {
        reply(Map.of("status", "completed"));
        assertThatThrownBy(() -> editor().edit(request)).isInstanceOfSatisfying(EditorFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo("OPENAI_USAGE_UNKNOWN");
            assertThat(failure.chargedUnknown()).isTrue();
        });
    }
}
