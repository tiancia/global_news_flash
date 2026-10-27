package com.globalnewsflash.digest.domain;

public record DigestRecord(String id, String digestDate, String target, String eventKey, String publicationId,
                        String body, String operationIdsPayload, String itemsPayload,
                        long windowStart, long windowEnd, long createdAt) {}
