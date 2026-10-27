package com.globalnewsflash.automation.domain;

public record BudgetPeriod(String id, long maxMicros, int maxRequests, long reservedMicros, long spentMicros, int usedRequests) {
    public static void requireConfiguration(String id, long maxMicros, int maxRequests) {
        requireId(id);
        if (maxMicros < 0 || maxRequests < 0) throw new IllegalArgumentException("Budget limits cannot be negative");
    }

    public void requireSameLimits(long micros, int requests) {
        if (maxMicros != micros || maxRequests != requests) throw new IllegalArgumentException("An existing period has immutable limits");
    }

    public boolean canReserve(long conservativeMicros) {
        return usedRequests < maxRequests && conservativeMicros <= maxMicros - spentMicros - reservedMicros;
    }

    public static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 120) throw new IllegalArgumentException("Invalid budget key");
    }
}
