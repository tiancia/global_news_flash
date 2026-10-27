package com.globalnewsflash.automation.domain;

import java.util.List;
import java.util.Objects;

public record Reservation(String id, String periodId, long amountMicros, Long actualMicros, String state, long createdAt) {
    public static void requireRequest(String id, String periodId, long conservativeMicros) {
        BudgetPeriod.requireId(id);
        BudgetPeriod.requireId(periodId);
        if (conservativeMicros < 0) throw new IllegalArgumentException("Cost cannot be negative");
    }

    public Reservation requireSameRequest(String period, long amount) {
        if (!periodId.equals(period) || amountMicros != amount) throw new IllegalArgumentException("Reservation key already has different parameters");
        return this;
    }

    public boolean transitionNeeded(String expected, String next) {
        if (next.equals(state)) return false;
        requireState(expected);
        return true;
    }

    public boolean settlementNeeded(long actual) {
        if ("SETTLED".equals(state)) {
            if (!Objects.equals(actualMicros, actual)) throw new IllegalArgumentException("Settlement differs");
            return false;
        }
        if (!List.of("IN_FLIGHT", "UNCERTAIN").contains(state)) throw new IllegalStateException("Reservation has not started");
        if (actual < 0 || actual > amountMicros) throw new IllegalArgumentException("Actual cost must fit the conservative reservation; investigate overage");
        return true;
    }

    public void requireReconciliation(long actual) {
        requireState("UNCERTAIN");
        if (actual < 0) throw new IllegalArgumentException("Actual cost cannot be negative");
    }

    public void requireState(String expected) {
        if (!expected.equals(state)) throw new IllegalStateException("Unexpected reservation state");
    }
}
