package com.globalnewsflash.automation.domain;

public record EditorJob(String id, String eventKey, int eventVersion, String fingerprint, String state,
                     String reservationId, long leaseUntil, long createdAt, long updatedAt, String lastError) {
    public boolean editing() { return "EDITING".equals(state); }
    public boolean leaseExpired(long now) { return leaseUntil <= now; }
    public void requireReconciliation() {
        if (!"UNCERTAIN".equals(state)) throw new IllegalStateException("Job is not uncertain");
    }
    public void requireRetryable() {
        if (!java.util.Set.of("ACCOUNTED", "RETRYABLE").contains(state)) throw new IllegalStateException("Only accounted or definitely rejected jobs can be retried");
    }
}

