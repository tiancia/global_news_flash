package com.globalnewsflash.publication.domain;

import java.time.Duration;

public record DeliveryResult(Outcome outcome, String messageId, Duration retryAfter, String error) {
    public enum Outcome { SUCCESS, RATE_LIMITED, UNKNOWN, FAILED }

    public static DeliveryResult success(String messageId) {
        return new DeliveryResult(Outcome.SUCCESS, messageId, null, null);
    }

    public static DeliveryResult rateLimited(Duration retryAfter) {
        return new DeliveryResult(Outcome.RATE_LIMITED, null, retryAfter, "RATE_LIMITED");
    }

    public static DeliveryResult unknown(String reason) {
        return new DeliveryResult(Outcome.UNKNOWN, null, null, reason);
    }

    public static DeliveryResult failed(String reason) {
        return new DeliveryResult(Outcome.FAILED, null, null, reason);
    }
}
