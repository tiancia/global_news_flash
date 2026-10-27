package com.globalnewsflash.ingestion.domain;

import java.util.Set;

/** Inbox state transitions and bounded retry decisions, independent of polling and persistence. */
public final class IngestionPolicy {
    private IngestionPolicy() {}

    public static void requireLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Inbox limit must be between 1 and 100");
    }

    public static void requireLiveMaterial(SourceMaterial material) {
        if (material.synthetic() || material.discoveryOnly()) throw new IllegalArgumentException("Invalid live inbox material");
    }

    public static void requireTerminal(String state) {
        if (state == null || !state.matches("[A-Z][A-Z0-9_]{0,39}") || Set.of("PENDING", "RETRY", "PROCESSING").contains(state))
            throw new IllegalArgumentException("A terminal inbox result is required");
    }

    public static void requireRequeueable(InboxRow row) {
        if (row == null) throw new IllegalArgumentException("Unknown inbox item");
        if (!Set.of("HELD", "FAILED", "EXPIRED", "REJECTED", "RETRY", "RETRY_WAIT", "RETRYABLE").contains(row.state()))
            throw new IllegalStateException("INBOX_RESULT_NOT_REQUEUEABLE");
    }

    public static boolean enabledAfterSync(boolean configured, boolean registered, Boolean previouslyEnabled) {
        return configured && (!registered || previouslyEnabled == null || previouslyEnabled);
    }

    public static String safe(String text, int max, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        if (text.length() > max || !text.matches("[A-Za-z0-9_:.=,; /-]+")) return fallback;
        return text;
    }

    public static long backoff(int attempts) {
        return Math.min(3_600_000L, 30_000L * (1L << Math.min(7, Math.max(0, attempts - 1))));
    }
}
