package com.globalnewsflash.ingestion.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Keeps persisted event identities bounded without truncating their external identifiers. */
final class StableEventKey {
    private StableEventKey() {}

    static String hashed(String sourceId, String externalId) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(externalId.getBytes(StandardCharsets.UTF_8)));
            return sourceId + ":" + hash;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 implementation unavailable", exception);
        }
    }
}
