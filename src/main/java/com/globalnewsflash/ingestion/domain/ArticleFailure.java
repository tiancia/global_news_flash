package com.globalnewsflash.ingestion.domain;

/** A bounded per-article retry journal, scoped to one immutable feed snapshot. */
public record ArticleFailure(String id, String sourceId, String snapshotKey, String itemKey,
        int attempts, String state, String reasonCode, long updatedAt) {
    public static final int MAX_ATTEMPTS = 3;
}
