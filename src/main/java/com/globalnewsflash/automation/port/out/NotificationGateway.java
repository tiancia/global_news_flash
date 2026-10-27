package com.globalnewsflash.automation.port.out;

/** Delivers one alert attempt; persistence, scheduling and retries belong to the caller. */
public interface NotificationGateway {
    record Notification(String id, String category, String subjectId, String reasonCode) {}

    /** False means delivery was not confirmed; the receiver must deduplicate by notification id. */
    boolean deliver(Notification notification);
}
