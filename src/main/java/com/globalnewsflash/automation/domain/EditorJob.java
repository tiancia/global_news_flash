package com.globalnewsflash.automation.domain;

public record EditorJob(String id, String eventKey, int eventVersion, String fingerprint, String state,
                     String reservationId, long leaseUntil, long createdAt, long updatedAt, String lastError) {
    public boolean editing() { return "EDITING".equals(state); }
    public boolean leaseExpired(long now) { return leaseUntil <= now; }
    public void requireReconciliation() {
        if (!"UNCERTAIN".equals(state)) throw new IllegalStateException("Job is not uncertain");
    }
    public void requireRetryable() {
        if (java.util.Set.of("ACCOUNTED", "RETRYABLE").contains(state)) return;
        if ("HELD".equals(state) && lastError != null && java.util.Set.of(
                "DUPLICATE_STATEMENT", "UNSUPPORTED_NUMBER", "UNSUPPORTED_QUOTE", "UNSUPPORTED_STATEMENT",
                "INVALID_ENGLISH_STATEMENT", "INVALID_EDITOR_RESPONSE", "DRAFT_TOO_LONG",
                "OPENAI_OUTPUT_INVALID", "OPENAI_RESPONSE_INCOMPLETE",
                "DEEPSEEK_OUTPUT_INVALID", "DEEPSEEK_RESPONSE_INCOMPLETE").contains(lastError)) return;
        throw new IllegalStateException("EDITOR_JOB_NOT_RETRYABLE");
    }
}

