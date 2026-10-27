package com.globalnewsflash.ingestion.infrastructure;

import static com.globalnewsflash.shared.Hashing.sha256;

/** Keeps persisted event identities bounded without truncating their external identifiers. */
final class StableEventKey {
    private StableEventKey() {}

    static String hashed(String sourceId, String externalId) {
        return sourceId + ":" + sha256(externalId);
    }
}
