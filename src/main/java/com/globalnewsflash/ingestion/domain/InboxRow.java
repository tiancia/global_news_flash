package com.globalnewsflash.ingestion.domain;

public record InboxRow(String id, String sourceId, String eventKey, String fingerprint, String materialPayload,
            String state, String detail, int attempts, long nextAttemptAt, String leaseOwner, long leaseUntil,
            long createdAt, Long completedAt) {}
