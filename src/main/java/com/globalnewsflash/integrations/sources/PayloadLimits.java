package com.globalnewsflash.integrations.sources;

import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

final class PayloadLimits {
    static final int MAX_CHARACTERS = 1_000_000;
    static final int MAX_ITEMS = 250;

    private PayloadLimits() {}

    static void check(String payload, Instant discoveredAt) {
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (payload == null || payload.isBlank() || payload.length() > MAX_CHARACTERS) {
            throw new IllegalArgumentException("Payload must contain at most " + MAX_CHARACTERS + " characters");
        }
    }

    static void checkMode(JsonNode root, boolean synthetic) {
        if (!synthetic && root != null && root.path("synthetic").asBoolean(false)) {
            throw new IllegalArgumentException("A synthetic fixture cannot be relabeled as real material");
        }
    }
}
