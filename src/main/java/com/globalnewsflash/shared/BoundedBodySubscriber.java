package com.globalnewsflash.shared;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/** Bounds the entire response before buffering; callers retain their own error codes and deadlines. */
public final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final Supplier<? extends Exception> tooLarge;
    private final Supplier<? extends Exception> unavailable;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    public BoundedBodySubscriber(int limit, Supplier<? extends Exception> tooLarge,
                                 Supplier<? extends Exception> unavailable) {
        this.limit = limit;
        this.tooLarge = tooLarge;
        this.unavailable = unavailable;
    }

    @Override public CompletionStage<byte[]> getBody() { return body; }

    @Override public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        subscription.request(1);
    }

    @Override public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > limit - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(tooLarge.get());
                return;
            }
            byte[] part = new byte[buffer.remaining()];
            buffer.get(part);
            bytes.writeBytes(part);
        }
        subscription.request(1);
    }

    @Override public void onError(Throwable failure) { body.completeExceptionally(unavailable.get()); }
    @Override public void onComplete() { body.complete(bytes.toByteArray()); }
}
