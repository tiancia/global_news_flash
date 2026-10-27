package com.globalnewsflash.automation.domain;

public final class NotificationPolicy {
    private NotificationPolicy() {}
    public static boolean exhausted(int attempts) { return attempts >= 5; }
    public static String state(boolean success, int attempts) { return success ? "SENT" : exhausted(attempts) ? "FAILED" : "PENDING"; }
    public static long retryAt(long now, int attempts) { return now + Math.min(3_600_000L, 30_000L << Math.min(attempts, 6)); }
}
