package com.globalnewsflash.editorial.domain;

import java.time.Instant;
import java.util.List;

public record EditorialResult(boolean eligible, List<String> reasons, EditorialDraft draft,
        String eventKey, String policyVersion, Instant evaluatedAt) {
    public EditorialResult {
        reasons = List.copyOf(reasons);
        if (eligible && (draft == null || !reasons.isEmpty())) {
            throw new IllegalArgumentException("An eligible result must have a draft and no rejection reasons");
        }
        if (!eligible && draft != null) {
            throw new IllegalArgumentException("A rejected result cannot expose a publishable draft");
        }
    }
}
