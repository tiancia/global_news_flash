package com.globalnewsflash.publication.domain;

import java.time.Instant;

public record DeliveryAttempt(String id, String operationId, int attemptNumber,
        String leaseToken, Instant startedAt, Instant finishedAt, String outcome, String error) {
}
