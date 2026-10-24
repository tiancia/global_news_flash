package com.globalnewsflash.publication.domain;

import java.time.Instant;

/** An immutable operation snapshot. Synthetic message IDs never identify Telegram messages. */
public record PublicationOperation(
        String id, String eventKey, int eventVersion, String target, String kind,
        String state, String body, String originalOperationId, String messageId,
        int attempts, String leaseToken, Instant leaseUntil, Instant nextAttemptAt,
        String lastError, boolean synthetic) {
}
