package com.globalnewsflash.publication.domain;

import java.time.Instant;

/** Persistent local alert. It does not imply notification to any external recipient. */
public record PublicationAlert(String id, String operationId, String category,
        String reasonCode, Instant createdAt, Instant acknowledgedAt) {
}
