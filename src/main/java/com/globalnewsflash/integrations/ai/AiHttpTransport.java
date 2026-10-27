package com.globalnewsflash.integrations.ai;

import com.globalnewsflash.editorial.domain.EditorFailure;
import com.globalnewsflash.integrations.http.BoundedBodySubscriber;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared transport only; provider adapters own schemas, HTTP status meanings and response envelopes. */
final class AiHttpTransport {
    private static final int MAX_RESPONSE_BYTES = 524_288;

    private AiHttpTransport() {}

    static HttpResponse<byte[]> post(HttpClient client, AiModelProperties properties, String payload) {
        HttpRequest outbound;
        try {
            outbound = HttpRequest.newBuilder(properties.endpoint()).timeout(properties.getTimeout())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        } catch (RuntimeException invalid) {
            throw new EditorFailure(properties.code("CONFIGURATION_INVALID"), false);
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(outbound, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES,
                    () -> new IOException("Response limit exceeded"), () -> new IOException("Response unavailable")));
            return pending.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new EditorFailure(properties.code("TIMEOUT"), true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EditorFailure(properties.code("INTERRUPTED"), true);
        } catch (ExecutionException transport) {
            throw new EditorFailure(properties.code(transport.getCause() instanceof HttpTimeoutException
                    ? "TIMEOUT" : "TRANSPORT_FAILED"), true);
        } catch (RuntimeException transport) {
            throw new EditorFailure(properties.code("TRANSPORT_FAILED"), true);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }
}
