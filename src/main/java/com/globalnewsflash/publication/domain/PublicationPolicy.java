package com.globalnewsflash.publication.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Publication invariants and state decisions; persistence and delivery are application responsibilities. */
public record PublicationPolicy(Duration lease, int maxAttempts, int maxPerHour, int maxPerDay, Duration minimumInterval) {
    public PublicationPolicy {
        if (lease == null || lease.isNegative() || lease.isZero() || maxAttempts < 1 || maxPerHour < 1 || maxPerDay < 1
                || minimumInterval == null || minimumInterval.isNegative()) {
            throw new IllegalArgumentException("Invalid publication retry, lease or cadence configuration");
        }
    }

    public static void requireMode(boolean synthetic, String mode, boolean externalEnabled) {
        if (synthetic && (!"OFFLINE".equals(mode) || externalEnabled)) throw new IllegalArgumentException("Synthetic publication requires OFFLINE mode");
        if (!synthetic && !("LIVE".equals(mode) && externalEnabled)) throw new IllegalArgumentException("Real publication requires LIVE mode and enabled external access");
    }

    public static void requireIntent(String eventKey, int eventVersion, String target, String kind, String body,
            String originalId, boolean synthetic, String configuredTarget) {
        requireText(eventKey, 255, "eventKey");
        requireText(target, 128, "target");
        requireText(body, 16000, "body");
        if (eventVersion < 1 || kind == null || !Set.of("SEND", "EDIT", "UPDATE", "CORRECTION").contains(kind))
            throw new IllegalArgumentException("Invalid event version or publication kind");
        if (!synthetic && !target.equals(configuredTarget)) throw new IllegalArgumentException("Real publication target must match the configured Telegram channel");
        if ("SEND".equals(kind) != (originalId == null)) throw new IllegalArgumentException("Updates, edits and corrections require an original operation");
    }

    public static void requireSame(PublicationOperation existing, String body, String originalId, boolean synthetic) {
        if (!existing.body().equals(body) || !Objects.equals(existing.originalOperationId(), originalId) || existing.synthetic() != synthetic)
            throw new IllegalArgumentException("An immutable publication version already has different content");
    }

    public static void requireOriginal(PublicationOperation operation, PublicationOperation original) {
        if (!"SENT".equals(original.state()) || original.messageId() == null || !original.target().equals(operation.target())
                || !original.eventKey().equals(operation.eventKey()) || original.synthetic() != operation.synthetic())
            throw new IllegalArgumentException("Original operation must be a sent message for the same event and target");
    }

    public static boolean requireResolution(String resolution, String messageId, String note) {
        requireText(note, 4000, "reconciliation note");
        if (!Set.of("CONFIRMED_SENT", "CONFIRMED_NOT_SENT").contains(resolution == null ? "" : resolution))
            throw new IllegalArgumentException("Invalid UNKNOWN resolution");
        boolean sent = "CONFIRMED_SENT".equals(resolution);
        if (sent) requireText(messageId, 128, "confirmed messageId");
        else if (messageId != null) throw new IllegalArgumentException("A not-sent resolution cannot have a message ID");
        return sent;
    }

    public static boolean reconciliationNeeded(PublicationOperation operation, List<PublicationResolution> previous,
            String resolution, String messageId) {
        if (!previous.isEmpty()) {
            var existing = previous.getFirst();
            if (existing.resolution().equals(resolution) && Objects.equals(existing.messageId(), messageId)) return false;
            throw new IllegalArgumentException("UNKNOWN was already resolved differently");
        }
        if (!"UNKNOWN".equals(operation.state())) throw new IllegalArgumentException("Only an UNKNOWN operation can be reconciled");
        return true;
    }

    public record DeliveryDecision(String state, Instant retryAt, String messageId, String error, Instant cooldownUntil) {}

    public DeliveryDecision finish(PublicationOperation operation, DeliveryResult result, String originalMessageId, Instant now) {
        return switch (result.outcome()) {
            case SUCCESS -> new DeliveryDecision("SENT", null, "EDIT".equals(operation.kind()) ? originalMessageId : result.messageId(), result.error(), null);
            case RATE_LIMITED -> new DeliveryDecision(operation.attempts() >= maxAttempts ? "FAILED" : "RETRY_WAIT",
                    operation.attempts() >= maxAttempts ? null : now.plus(result.retryAfter()), null,
                    operation.attempts() >= maxAttempts ? "RETRY_LIMIT_REACHED" : result.error(),
                    operation.synthetic() ? null : now.plus(result.retryAfter()));
            case UNKNOWN -> new DeliveryDecision("UNKNOWN", null, null, result.error(), null);
            case FAILED -> new DeliveryDecision("FAILED", null, null, result.error(), null);
        };
    }

    public static List<String> guardReasons(List<List<String>> results) {
        var reasons = new LinkedHashSet<String>();
        for (var result : results) {
            if (result == null) reasons.add("GUARD_INVALID_RESULT");
            else result.stream().limit(20).forEach(reason -> {
                String code = reason == null ? "" : reason.split(":", 2)[0];
                reasons.add(code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : "GUARD_INVALID_REASON");
            });
        }
        return List.copyOf(reasons);
    }

    public static DeliveryResult validateResult(DeliveryResult result) {
        if (result == null || result.outcome() == null) {
            return DeliveryResult.unknown("INVALID_GATEWAY_RESULT");
        }
        if (result.outcome() == DeliveryResult.Outcome.SUCCESS
                && (result.messageId() == null || result.messageId().isBlank() || result.messageId().length() > 128)) {
            return DeliveryResult.unknown("MISSING_MESSAGE_ID");
        }
        if (result.outcome() == DeliveryResult.Outcome.RATE_LIMITED
                && (result.retryAfter() == null || result.retryAfter().isNegative()
                    || result.retryAfter().isZero() || result.retryAfter().compareTo(java.time.Duration.ofDays(7)) > 0)) {
            return DeliveryResult.unknown("INVALID_RETRY_AFTER");
        }
        return result;
    }

    public static String safeError(String error) {
        // Gateway contracts return stable codes, not raw response bodies or exception messages.
        if (error == null) {
            return null;
        }
        return error.matches("[A-Z][A-Z0-9_]{0,127}") ? error : "GATEWAY_ERROR";
    }

    public static void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }
}
