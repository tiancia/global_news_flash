package com.globalnewsflash.publication.application;

import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.publication.domain.DeliveryAttempt;
import com.globalnewsflash.publication.domain.DeliveryResult;
import com.globalnewsflash.publication.domain.PublicationAlert;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.domain.PublicationHistory;
import com.globalnewsflash.publication.domain.PublicationPolicy;
import com.globalnewsflash.publication.domain.PublicationResolution;
import com.globalnewsflash.publication.domain.PublicationText;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import com.globalnewsflash.publication.port.out.DeliveryGateway;
import com.globalnewsflash.publication.port.out.PublicationGuard;
import com.globalnewsflash.publication.port.out.PublicationRepository;
import com.globalnewsflash.publication.port.out.PublicationSettings;
import com.globalnewsflash.publication.port.out.PublicationTarget;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists delivery intent before crossing the gateway boundary. A missing result after
 * SENDING is never treated as permission to send again. Local CAS does not guarantee
 * exactly-once external effects.
 */
@Service
public class PublicationService implements PublicationUseCase {
    private final PublicationRepository mapper;
    private final DeliveryGateway gateway;
    private final Clock clock;
    private final PublicationSettings properties;
    private final TransactionTemplate transactions;
    private final TransactionTemplate enqueueTransactions;
    private final List<PublicationGuard> guards;
    private final RuntimeSettings runtime;
    private final PublicationTarget telegram;

    public PublicationService(PublicationRepository mapper, DeliveryGateway gateway, Clock clock,
            PublicationSettings properties, PlatformTransactionManager transactionManager,
            List<PublicationGuard> guards) {
        this(mapper, gateway, clock, properties, transactionManager, guards,
                RuntimeSettings.offline(), PublicationTarget.disabled());
    }

    @Autowired
    public PublicationService(PublicationRepository mapper, DeliveryGateway gateway, Clock clock,
            PublicationSettings properties, PlatformTransactionManager transactionManager,
            List<PublicationGuard> guards, RuntimeSettings runtime, PublicationTarget telegram) {
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
        policy();
    }

    public PublicationOperation enqueue(String eventKey, int eventVersion, String target,
            String kind, String body, String originalOperationId, boolean synthetic) {
        requireMode(synthetic);
        PublicationPolicy.requireIntent(eventKey, eventVersion, target, kind, body, originalOperationId, synthetic, telegram.getChatId());
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
                PublicationPolicy.requireSame(existing, body, originalOperationId, synthetic);
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
        boolean sent = PublicationPolicy.requireResolution(resolution, messageId, note);
        return transactions.execute(status -> {
            PublicationOperation operation = mapper.lockFind(id);
            if (operation == null) {
                throw new IllegalArgumentException("Publication operation not found");
            }
            List<PublicationResolution> previous = mapper.resolutions(id);
            if (!PublicationPolicy.reconciliationNeeded(operation, previous, resolution, messageId)) return operation;
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
        DeliveryResult finalResult = PublicationPolicy.validateResult(result);
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
        String originalMessageId = result.outcome() == DeliveryResult.Outcome.SUCCESS && "EDIT".equals(operation.kind())
                ? get(operation.originalOperationId()).messageId() : null;
        var decision = policy().finish(operation, result, originalMessageId, now);
        String state = decision.state();
        Instant retryAt = decision.retryAt();
        String messageId = decision.messageId();
        String error = decision.error();
        if (decision.cooldownUntil() != null) mapper.coolDownChannel(operation.target(), decision.cooldownUntil());
        if (mapper.finish(operation.id(), token, state, messageId, retryAt, PublicationPolicy.safeError(error), now) == 1) {
            mapper.finishAttempt(operation.id(), token, result.outcome().name(), PublicationPolicy.safeError(error), now);
            if ("UNKNOWN".equals(state) || "FAILED".equals(state)) {
                alert(operation.id(), state, PublicationPolicy.safeError(error) == null ? "DELIVERY_" + state : PublicationPolicy.safeError(error), now);
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
        if (operation.originalOperationId() != null) PublicationPolicy.requireOriginal(operation, get(operation.originalOperationId()));
    }

    private void requireMode(boolean synthetic) {
        PublicationPolicy.requireMode(synthetic, runtime.getMode(), runtime.isExternalEnabled());
    }

    /** Called while the pause-control lock is held; the durable target row serializes all send attempts. */
    private Instant reserveCadence(String target, Instant now) {
        var current = mapper.lockChannelLimit(target);
        if (current == null) {
            mapper.insertChannelLimit(target, now.truncatedTo(ChronoUnit.HOURS).getEpochSecond(), now.truncatedTo(ChronoUnit.DAYS).getEpochSecond(), now);
            current = mapper.lockChannelLimit(target);
        }
        var decision = current.reserve(now, policy());
        if (decision.reserved() != null) mapper.saveChannelLimit(decision.reserved());
        return decision.retryAt();
    }

    private List<String> guardRejections(PublicationOperation operation) {
        try {
            var results = new java.util.ArrayList<List<String>>();
            for (var guard : guards) results.add(guard.rejectionReasons(operation));
            return PublicationPolicy.guardReasons(results);
        } catch (RuntimeException exception) {
            return List.of("GUARD_CHECK_FAILED");
        }
    }


    private PublicationPolicy policy() {
        return new PublicationPolicy(properties.getLease(), properties.getMaxAttempts(), properties.getMaxPerHour(), properties.getMaxPerDay(), properties.getMinimumInterval());
    }
    public String previewText(String id) { return PublicationText.render(get(id)); }

    public PublicationOperation latestConfirmed(String eventKey, String target) {
        return PublicationHistory.latestConfirmed(mapper.list(), eventKey, target);
    }
}
