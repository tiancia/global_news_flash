package com.globalnewsflash.editorial.domain;

import java.time.Duration;
import java.util.Set;

/** Explicit policy values; no operating thresholds are inferred from missing configuration. */
public record EditorialPolicy(String version, Duration maxAge, int maxCharacters,
        int minimumIndependentChains, Set<String> allowedTopics, boolean ready) {
    public EditorialPolicy {
        allowedTopics = allowedTopics == null ? Set.of() : Set.copyOf(allowedTopics);
    }

    public boolean configured() {
        return ready && version != null && !version.isBlank() && maxAge != null
                && maxAge.isPositive() && maxCharacters > 0 && minimumIndependentChains > 0
                && !allowedTopics.isEmpty();
    }
}
