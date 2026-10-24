package com.globalnewsflash.editorial.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EditorRequest(String requestId, String eventKey, String promptVersion,
        String contractVersion, List<EvidenceFact> facts, Instant editedAt, boolean synthetic) {
    public EditorRequest {
        facts = List.copyOf(facts);
        Objects.requireNonNull(editedAt, "editedAt");
    }

    public record EvidenceFact(String sourceId, String materialId, String factId,
            String text, String sourceUrl) {
    }
}
