package com.globalnewsflash.ingestion.domain;

public record SourceState(String sourceId, boolean registered, String cursorValue, String etag, String lastModified,
            Long lastAttemptAt, Long lastSuccessAt, long nextPollAt, int consecutiveFailures, int lastItemCount,
            String errorCode, String leaseOwner, long leaseUntil, String snapshotPayload, String snapshotEtag,
            String snapshotModified, int snapshotOffset, Long snapshotCreatedAt) {}
