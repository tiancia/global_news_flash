package com.globalnewsflash.publication.domain;

import java.time.Instant;

/** Operator-supplied reconciliation evidence; notes are stored, never logged. */
public record PublicationResolution(String id, String operationId, String resolution,
        String messageId, String note, Instant createdAt) {
}
