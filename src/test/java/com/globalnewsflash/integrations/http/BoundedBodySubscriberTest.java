package com.globalnewsflash.integrations.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BoundedBodySubscriberTest {
    private final Flow.Subscription subscription = mock(Flow.Subscription.class);

    private BoundedBodySubscriber subscriber(int limit) {
        var subscriber = new BoundedBodySubscriber(limit,
                () -> new IllegalStateException("TOO_LARGE"), () -> new IllegalStateException("UNAVAILABLE"));
        subscriber.onSubscribe(subscription);
        return subscriber;
    }

    @Test void acceptsMultipleBuffersExactlyAtLimit() {
        var subscriber = subscriber(3);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1}), ByteBuffer.wrap(new byte[]{2, 3})));
        subscriber.onComplete();
        assertThat(subscriber.getBody().toCompletableFuture().join()).containsExactly(1, 2, 3);
        verify(subscription, never()).cancel();
    }

    @Test void cancelsAcrossChunksAndDoesNotConsumeOversizedBuffer() {
        var subscriber = subscriber(3);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2})));
        var overflow = ByteBuffer.wrap(new byte[]{3, 4});
        subscriber.onNext(List.of(overflow));
        subscriber.onComplete();
        assertThat(overflow.position()).isZero();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join()).hasRootCauseMessage("TOO_LARGE");
        verify(subscription).cancel();
    }

    @Test void countsBytesRatherThanCharacters() {
        var subscriber = subscriber(2);
        subscriber.onNext(List.of(ByteBuffer.wrap("新".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join()).hasRootCauseMessage("TOO_LARGE");
    }

    @Test void sanitizesUpstreamErrors() {
        var subscriber = subscriber(10);
        subscriber.onError(new IllegalStateException("private upstream body"));
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .satisfies(failure -> assertThat(failure.getCause()).hasMessage("UNAVAILABLE").hasNoCause());
    }
}
