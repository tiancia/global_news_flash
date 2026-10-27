package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.domain.BudgetPeriod;
import com.globalnewsflash.automation.domain.Reservation;
import com.globalnewsflash.automation.port.in.BudgetUseCase;
import com.globalnewsflash.automation.port.out.BudgetRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Durable accounting. Limits and model prices are explicit, never inferred. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BudgetService implements BudgetUseCase {
    private final BudgetRepository mapper;
    private final Clock clock;
    public BudgetService(BudgetRepository mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    @Transactional
    public void configureSyntheticPeriod(String id, long maxMicros, int maxRequests) {
        configurePeriod(id, maxMicros, maxRequests);
    }

    @Transactional
    public void configurePeriod(String id, long maxMicros, int maxRequests) {
        BudgetPeriod.requireConfiguration(id, maxMicros, maxRequests);
        try { mapper.create(id, maxMicros, maxRequests); }
        catch (DuplicateKeyException exists) {
            var current = mapper.lockPeriod(id);
            current.requireSameLimits(maxMicros, maxRequests);
        }
    }

    @Transactional
    public Reservation reserve(String id, String periodId, long conservativeMicros) {
        var result = tryReserve(id, periodId, conservativeMicros);
        if (result == null) throw new IllegalStateException("BUDGET_EXHAUSTED");
        return result;
    }

    @Transactional
    public Reservation tryReserve(String id, String periodId, long conservativeMicros) {
        Reservation.requireRequest(id, periodId, conservativeMicros);
        var budget = mapper.lockPeriod(periodId);
        if (budget == null) throw new IllegalArgumentException("Budget not configured");
        var previous = mapper.lockReservation(id);
        if (previous != null) return previous.requireSameRequest(periodId, conservativeMicros);
        if (!budget.canReserve(conservativeMicros)) return null;
        var result = new Reservation(id, periodId, conservativeMicros, null, "RESERVED", clock.millis());
        mapper.insert(result);
        mapper.reserve(periodId, conservativeMicros);
        return result;
    }

    @Transactional
    public void markStarted(String id) {
        var reservation = locked(id);
        if (!reservation.transitionNeeded("RESERVED", "IN_FLIGHT")) return;
        mapper.state(id, "IN_FLIGHT", null);
    }

    @Transactional
    public void markUncertain(String id) {
        var reservation = locked(id);
        if (!reservation.transitionNeeded("IN_FLIGHT", "UNCERTAIN")) return;
        mapper.state(id, "UNCERTAIN", null);
    }

    @Transactional
    public void cancelBeforeCall(String id) {
        var reservation = locked(id);
        if (!reservation.transitionNeeded("RESERVED", "CANCELLED")) return;
        mapper.finish(reservation.periodId(), reservation.amountMicros(), 0, 1);
        mapper.state(id, "CANCELLED", null);
    }

    @Transactional
    public void settle(String id, long actualMicros) {
        var reservation = locked(id);
        if (!reservation.settlementNeeded(actualMicros)) return;
        mapper.finish(reservation.periodId(), reservation.amountMicros(), actualMicros, 0);
        mapper.state(id, "SETTLED", actualMicros);
    }

    public List<BudgetPeriod> report() { return List.copyOf(mapper.periods()); }
    public Reservation get(String id) { return mapper.find(id); }

    /** Explicit operator reconciliation may exceed an estimate; future reservations still honor the cap. */
    @Transactional
    public void reconcileUncertain(String id, long actualMicros) {
        var reservation = locked(id);
        reservation.requireReconciliation(actualMicros);
        mapper.finish(reservation.periodId(), reservation.amountMicros(), actualMicros, 0);
        mapper.state(id, "SETTLED", actualMicros);
    }

    private Reservation locked(String id) {
        BudgetPeriod.requireId(id);
        var existing = mapper.find(id);
        if (existing == null) throw new IllegalArgumentException("Unknown reservation");
        mapper.lockPeriod(existing.periodId());
        return mapper.lockReservation(id);
    }
}
