package com.globalnewsflash.publication.application;

import com.globalnewsflash.publication.domain.DeliveryAttempt;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.domain.PublicationAlert;
import com.globalnewsflash.publication.domain.PublicationResolution;
import com.globalnewsflash.publication.infrastructure.PublicationMapper;
import com.globalnewsflash.publication.infrastructure.PublicationProperties;
import com.globalnewsflash.publication.infrastructure.TelegramProperties;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists delivery intent before crossing the gateway boundary. A missing result after
 * SENDING is never treated as permission to send again. Local CAS does not guarantee
 * exactly-once external effects.
 */
@Service
public class PublicationService {
    private static final Set<String> KINDS = Set.of("SEND", "EDIT", "UPDATE", "CORRECTION");
    private final PublicationMapper mapper;
    private final DeliveryGateway gateway;
    private final Clock clock;
    private final PublicationProperties properties;
    private final TransactionTemplate transactions;
    private final TransactionTemplate enqueueTransactions;
    private final List<PublicationGuard> guards;
    private final RuntimeProperties runtime;
    private final TelegramProperties telegram;

    public PublicationService(PublicationMapper mapper, DeliveryGateway gateway, Clock clock,
            PublicationProperties properties, PlatformTransactionManager transactionManager,
            List<PublicationGuard> guards) {
        this(mapper, gateway, clock, properties, transactionManager, guards,
                new RuntimeProperties(), new TelegramProperties());
    }

    @Autowired
    public PublicationService(PublicationMapper mapper, DeliveryGateway gateway, Clock clock,
            PublicationProperties properties, PlatformTransactionManager transactionManager,
            List<PublicationGuard> guards, RuntimeProperties runtime, TelegramProperties telegram) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.clock = clock;
        this.properties = properties;
        this.guards = List.copyOf(guards);
        this.runtime = runtime;
        this.telegram = telegram;
        this.transactions = new TransactionTemplate(transactionManager);
        // Enqueue joins the pipeline transaction so the event version and its task commit atomically.
        this.enqueueTransactions = new TransactionTemplate(transactionManager);
        // An outer application transaction must not postpone committing SENDING until after delivery.
        this.transactions.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        if (properties.getLease() == null || properties.getLease().isNegative()
                || properties.getLease().isZero() || properties.getMaxAttempts() < 1
                || properties.getMaxPerHour() < 1 || properties.getMaxPerDay() < 1
                || properties.getMinimumInterval() == null || properties.getMinimumInterval().isNegative()) {
            throw new IllegalArgumentException("Invalid publication retry, lease or cadence configuration");
        }
    }

    public PublicationOperation enqueue(String eventKey, int eventVersion, String target,
            String kind, String body, String originalOperationId, boolean synthetic) {
        requireText(eventKey, 255, "eventKey");
        requireText(target, 128, "target");
        requireText(body, 16000, "body");
        if (eventVersion < 1 || kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("Invalid event version or publication kind");
        }
        requireMode(synthetic);
        if (!synthetic && !target.equals(telegram.getChatId())) {
            throw new IllegalArgumentException("Real publication target must match the configured Telegram channel");
        }
        if ("SEND".equals(kind) != (originalOperationId == null)) {
            throw new IllegalArgumentException("Updates, edits and corrections require an original operation");
        }
        PublicationOperation operation = new PublicationOperation(UUID.randomUUID().toString(),
                eventKey, eventVersion, target, kind, "PENDING", body, originalOperationId,
                null, 0, null, null, null, null, synthetic);
        return enqueueTransactions.execute(status -> {
            validateOriginal(operation);
            try {
                mapper.insert(operation, clock.instant());
                return get(operation.id());
            } catch (DuplicateKeyException duplicate) {
                PublicationOperation existing = mapper.findKey(eventKey, eventVersion, target, kind);
                if (existing == null) {
                    throw duplicate;
                }
                if (!existing.body().equals(body)
                        || !Objects.equals(existing.originalOperationId(), originalOperationId)
                        || existing.synthetic() != synthetic) {
                    throw new IllegalArgumentException("An immutable publication version already has different content");
                }
                return existing;
            }
        });
    }

    public PublicationOperation get(String id) {
        PublicationOperation operation = mapper.find(id);
        if (operation == null) {
            throw new IllegalArgumentException("Publication operation not found");
        }
        return operation;
    }

    public List<PublicationOperation> list() {
        return List.copyOf(mapper.list());
    }

    public List<DeliveryAttempt> attempts(String id) {
        get(id);
        return List.copyOf(mapper.attempts(id));
    }

    public List<PublicationAlert> alerts() {
        return List.copyOf(mapper.alerts());
    }
    public List<PublicationAlert> unnotifiedAlerts() { return List.copyOf(mapper.unnotifiedAlerts()); }

    public List<PublicationResolution> resolutions(String id) {
        get(id);
        return List.copyOf(mapper.resolutions(id));
    }

    public PublicationOperation resolveUnknown(String id, String resolution, String messageId, String note) {
        requireText(note, 4000, "reconciliation note");
        if (!Set.of("CONFIRMED_SENT", "CONFIRMED_NOT_SENT").contains(resolution == null ? "" : resolution)) {
            throw new IllegalArgumentException("Invalid UNKNOWN resolution");
        }
        boolean sent = "CONFIRMED_SENT".equals(resolution);
        if (sent) {
            requireText(messageId, 128, "confirmed messageId");
        } else if (messageId != null) {
            throw new IllegalArgumentException("A not-sent resolution cannot have a message ID");
        }
        return transactions.execute(status -> {
            PublicationOperation operation = mapper.lockFind(id);
            if (operation == null) {
                throw new IllegalArgumentException("Publication operation not found");
            }
            List<PublicationResolution> previous = mapper.resolutions(id);
            if (!previous.isEmpty()) {
                PublicationResolution existing = previous.getFirst();
                if (existing.resolution().equals(resolution)
                        && Objects.equals(existing.messageId(), messageId)) {
                    return operation;
                }
                throw new IllegalArgumentException("UNKNOWN was already resolved differently");
            }
            if (!"UNKNOWN".equals(operation.state())) {
                throw new IllegalArgumentException("Only an UNKNOWN operation can be reconciled");
            }
            String nextState = sent ? "SENT" : "FAILED";
            if (mapper.resolveUnknown(id, nextState, messageId, resolution) != 1) {
                throw new IllegalStateException("UNKNOWN changed during reconciliation");
            }
            Instant now = clock.instant();
            mapper.insertResolution(new PublicationResolution(UUID.randomUUID().toString(), id,
                    resolution, messageId, note, now));
            if (!sent) {
                alert(id, "FAILED", "MANUAL_CONFIRMED_NOT_SENT", now);
            }
            mapper.acknowledgeAlerts(id, now);
            return get(id);
        });
    }

    /** A bounded worker iteration. Pausing prevents delivery but not crash recovery. */
    public int processDueBatch() {
        recoverExpired();
        if (isPaused()) {
            return 0;
        }
        if ("LIVE".equals(runtime.getMode()) && !telegram.isEnabled()) return 0;
        int processed = 0;
        for (PublicationOperation operation : mapper.due(clock.instant())) {
            try {
                dispatch(operation.id());
                processed++;
            } catch (RuntimeException exception) {
                // Stable codes only: raw exception messages can contain credentials or request content.
                // If persistence is unavailable this failure propagates; it is never silently swallowed.
                transactions.executeWithoutResult(status ->
                        alert(operation.id(), "WORKER_ERROR", "WORKER_DISPATCH_EXCEPTION", clock.instant()));
            }
        }
        return processed;
    }

    public void setPaused(boolean paused) {
        transactions.executeWithoutResult(status -> {
            mapper.lockPaused();
            mapper.setPaused(paused);
            mapper.auditPause(UUID.randomUUID().toString(), paused, clock.instant());
        });
    }

    public boolean isPaused() {
        return mapper.paused();
    }

    public PublicationOperation dispatch(String id) {
        PublicationOperation requested = get(id);
        // Disabled delivery preserves preview tasks for later enablement without claiming or cancelling them.
        if (!requested.synthetic() && !telegram.isEnabled()) return requested;
        String token = UUID.randomUUID().toString();
        boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> {
            if (mapper.lockPaused()) {
                return false;
            }
            Instant now = clock.instant();
            return mapper.claim(id, token, now.plus(properties.getLease()), now) == 1;
        }));
        if (!claimed) {
            return get(id);
        }

        PublicationOperation sending = transactions.execute(status -> {
            if (mapper.lockPaused()) {
                mapper.releaseClaim(id, token);
                return null;
            }
            PublicationOperation claimedOperation = get(id);
            requireMode(claimedOperation.synthetic());
            List<String> rejections = guardRejections(claimedOperation);
            if (!rejections.isEmpty()) {
                if (mapper.cancelClaim(id, token) == 1) {
                    for (String reason : rejections) {
                        alert(id, "ELIGIBILITY_REVOKED", reason, clock.instant());
                    }
                }
                return null;
            }
            Instant now = clock.instant();
            if (!claimedOperation.synthetic()) {
                Instant retryAt = reserveCadence(claimedOperation.target(), now);
                if (retryAt != null) {
                    mapper.deferClaim(id, token, retryAt);
                    return null;
                }
            }
            if (mapper.beginSending(id, token, now) != 1) {
                return null;
            }
            PublicationOperation snapshot = get(id);
            requireMode(snapshot.synthetic());
            mapper.insertAttempt(new DeliveryAttempt(UUID.randomUUID().toString(), id,
                    snapshot.attempts(), token, now, null, "SENDING", null));
            return snapshot;
        });
        if (sending == null) {
            return get(id);
        }

        DeliveryResult result;
        try {
            result = gateway.deliver(sending);
        } catch (RuntimeException exception) {
            // A gateway exception can occur after the remote side effect. Never automatically resend.
            result = DeliveryResult.unknown("GATEWAY_EXCEPTION_" + exception.getClass().getSimpleName());
        }
        DeliveryResult finalResult = validateResult(result);
        transactions.executeWithoutResult(status -> finish(sending, token, finalResult));
        return get(id);
    }

    public int recoverExpired() {
        Integer recovered = transactions.execute(status -> {
            Instant now = clock.instant();
            int count = 0;
            for (PublicationOperation operation : mapper.expired(now)) {
                boolean sending = "SENDING".equals(operation.state());
                String next = sending ? "UNKNOWN" : "PENDING";
                String error = sending ? "RECOVERED_WITHOUT_DELIVERY_RESULT" : "CLAIM_LEASE_EXPIRED";
                int changed = mapper.recover(operation.id(), operation.state(), operation.leaseToken(),
                        next, error, now);
                if (changed == 1 && sending) {
                    mapper.finishAttempt(operation.id(), operation.leaseToken(), "UNKNOWN", error, now);
                    alert(operation.id(), "UNKNOWN", error, now);
                }
                count += changed;
            }
            return count;
        });
        return recovered == null ? 0 : recovered;
    }

    private void finish(PublicationOperation operation, String token, DeliveryResult result) {
        Instant now = clock.instant();
        String state;
        Instant retryAt = null;
        String messageId = null;
        String error = result.error();
        switch (result.outcome()) {
            case SUCCESS -> {
                state = "SENT";
                messageId = "EDIT".equals(operation.kind())
                        ? get(operation.originalOperationId()).messageId() : result.messageId();
            }
            case RATE_LIMITED -> {
                if (!operation.synthetic()) mapper.coolDownChannel(operation.target(), now.plus(result.retryAfter()));
                if (operation.attempts() >= properties.getMaxAttempts()) {
                    state = "FAILED";
                    error = "RETRY_LIMIT_REACHED";
                } else {
                    state = "RETRY_WAIT";
                    retryAt = now.plus(result.retryAfter());
                }
            }
            case UNKNOWN -> state = "UNKNOWN";
            case FAILED -> state = "FAILED";
            default -> throw new IllegalStateException("Unhandled delivery result");
        }
        if (mapper.finish(operation.id(), token, state, messageId, retryAt, safeError(error), now) == 1) {
            mapper.finishAttempt(operation.id(), token, result.outcome().name(), safeError(error), now);
            if ("UNKNOWN".equals(state) || "FAILED".equals(state)) {
                alert(operation.id(), state, safeError(error) == null ? "DELIVERY_" + state : safeError(error), now);
            }
        } else {
            // Preserve a late response without letting an expired worker overwrite current state.
            if (mapper.recover(operation.id(), "SENDING", token, "UNKNOWN", "LEASE_EXPIRED_RESULT_UNKNOWN", now) == 1) {
                alert(operation.id(), "UNKNOWN", "LEASE_EXPIRED_RESULT_UNKNOWN", now);
            }
            mapper.finishAttempt(operation.id(), token, "LATE_" + result.outcome().name(),
                    "LATE_RESULT_REQUIRES_REVIEW", now);
        }
    }

    private void alert(String operationId, String category, String reasonCode, Instant now) {
        try {
            mapper.insertAlert(new PublicationAlert(UUID.randomUUID().toString(), operationId,
                    category, reasonCode, now, null));
        } catch (DuplicateKeyException duplicate) {
            // Repeated worker ticks preserve the original alert instead of flooding the local journal.
            // MySQL and the offline H2 profile permit continuing after a duplicate-key statement.
        }
    }

    private void validateOriginal(PublicationOperation operation) {
        if (operation.originalOperationId() == null) {
            return;
        }
        PublicationOperation original = get(operation.originalOperationId());
        if (!"SENT".equals(original.state()) || original.messageId() == null
                || !original.target().equals(operation.target())
                || !original.eventKey().equals(operation.eventKey())
                || original.synthetic() != operation.synthetic()) {
            throw new IllegalArgumentException("Original operation must be a sent message for the same event and target");
        }
    }

    private void requireMode(boolean synthetic) {
        boolean live = "LIVE".equals(runtime.getMode()) && runtime.isExternalEnabled();
        if (synthetic && (!"OFFLINE".equals(runtime.getMode()) || runtime.isExternalEnabled()))
            throw new IllegalArgumentException("Synthetic publication requires OFFLINE mode");
        if (!synthetic && !live)
            throw new IllegalArgumentException("Real publication requires LIVE mode and enabled external access");
    }

    /** Called while the pause-control lock is held; the durable target row serializes all send attempts. */
    private Instant reserveCadence(String target, Instant now) {
        long hour = now.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        long day = now.truncatedTo(ChronoUnit.DAYS).getEpochSecond();
        PublicationMapper.ChannelLimit current = mapper.lockChannelLimit(target);
        if (current == null) {
            mapper.insertChannelLimit(target, hour, day, now);
            current = mapper.lockChannelLimit(target);
        }
        int hourCount = current.hourStart() == hour ? current.hourCount() : 0;
        int dayCount = current.dayStart() == day ? current.dayCount() : 0;
        Instant retry = current.nextAllowedAt().isAfter(now) ? current.nextAllowedAt() : null;
        if (hourCount >= properties.getMaxPerHour()) {
            Instant nextHour = Instant.ofEpochSecond(hour).plus(1, ChronoUnit.HOURS);
            retry = retry == null || nextHour.isAfter(retry) ? nextHour : retry;
        }
        if (dayCount >= properties.getMaxPerDay()) {
            Instant nextDay = Instant.ofEpochSecond(day).plus(1, ChronoUnit.DAYS);
            retry = retry == null || nextDay.isAfter(retry) ? nextDay : retry;
        }
        if (retry != null) return retry;
        mapper.saveChannelLimit(new PublicationMapper.ChannelLimit(target, hour, hourCount + 1,
                day, dayCount + 1, now.plus(properties.getMinimumInterval())));
        return null;
    }

    private List<String> guardRejections(PublicationOperation operation) {
        try {
            Set<String> stableReasons = new LinkedHashSet<>();
            for (PublicationGuard guard : guards) {
                List<String> reasons = guard.rejectionReasons(operation);
                if (reasons == null) {
                    stableReasons.add("GUARD_INVALID_RESULT");
                } else {
                    reasons.stream().limit(20).forEach(reason -> {
                        // Keep the policy code; IDs and raw external text after ':' are not alert content.
                        String code = reason == null ? "" : reason.split(":", 2)[0];
                        stableReasons.add(code.matches("[A-Z][A-Z0-9_]{0,127}")
                                ? code : "GUARD_INVALID_REASON");
                    });
                }
            }
            return List.copyOf(stableReasons);
        } catch (RuntimeException exception) {
            // Eligibility errors cannot be used as a reason to continue with delivery.
            return List.of("GUARD_CHECK_FAILED");
        }
    }

    private static DeliveryResult validateResult(DeliveryResult result) {
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

    private static String safeError(String error) {
        // Gateway contracts return stable codes, not raw response bodies or exception messages.
        if (error == null) {
            return null;
        }
        return error.matches("[A-Z][A-Z0-9_]{0,127}") ? error : "GATEWAY_ERROR";
    }

    private static void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }
}
