package com.globalnewsflash.automation.application;

import com.globalnewsflash.automation.infrastructure.BudgetMapper;
import com.globalnewsflash.automation.infrastructure.BudgetMapper.Reservation;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Durable accounting. Limits and model prices are explicit, never inferred. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BudgetService {
    private final BudgetMapper mapper;
    private final Clock clock;
    public BudgetService(BudgetMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    @Transactional
    public void configureSyntheticPeriod(String id, long maxMicros, int maxRequests) {
        configurePeriod(id, maxMicros, maxRequests);
    }

    @Transactional
    public void configurePeriod(String id, long maxMicros, int maxRequests) {
        requireId(id);
        if (maxMicros < 0 || maxRequests < 0) throw new IllegalArgumentException("Budget limits cannot be negative");
        try { mapper.create(id, maxMicros, maxRequests); }
        catch (DuplicateKeyException exists) {
            var current = mapper.lockPeriod(id);
            if (current.maxMicros() != maxMicros || current.maxRequests() != maxRequests) {
                throw new IllegalArgumentException("An existing period has immutable limits");
            }
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
        requireId(id); requireId(periodId);
        if (conservativeMicros < 0) throw new IllegalArgumentException("Cost cannot be negative");
        var budget = mapper.lockPeriod(periodId);
        if (budget == null) throw new IllegalArgumentException("Budget not configured");
        var previous = mapper.lockReservation(id);
        if (previous != null) {
            if (!periodId.equals(previous.periodId()) || conservativeMicros != previous.amountMicros()) {
                throw new IllegalArgumentException("Reservation key already has different parameters");
            }
            return previous;
        }
        if (budget.usedRequests() >= budget.maxRequests()
                || conservativeMicros > budget.maxMicros() - budget.spentMicros() - budget.reservedMicros()) {
            return null;
        }
        var result = new Reservation(id, periodId, conservativeMicros, null, "RESERVED", clock.millis());
        mapper.insert(result);
        mapper.reserve(periodId, conservativeMicros);
        return result;
    }

    @Transactional
    public void markStarted(String id) {
        var reservation = locked(id);
        if ("IN_FLIGHT".equals(reservation.state())) return;
        requireState(reservation, "RESERVED");
        mapper.state(id, "IN_FLIGHT", null);
    }

    @Transactional
    public void markUncertain(String id) {
        var reservation = locked(id);
        if ("UNCERTAIN".equals(reservation.state())) return;
        requireState(reservation, "IN_FLIGHT");
        mapper.state(id, "UNCERTAIN", null);
    }

    @Transactional
    public void cancelBeforeCall(String id) {
        var reservation = locked(id);
        if ("CANCELLED".equals(reservation.state())) return;
        requireState(reservation, "RESERVED");
        mapper.finish(reservation.periodId(), reservation.amountMicros(), 0, 1);
        mapper.state(id, "CANCELLED", null);
    }

    @Transactional
    public void settle(String id, long actualMicros) {
        var reservation = locked(id);
        if ("SETTLED".equals(reservation.state())) {
            if (!Objects.equals(reservation.actualMicros(), actualMicros)) throw new IllegalArgumentException("Settlement differs");
            return;
        }
        if (!List.of("IN_FLIGHT", "UNCERTAIN").contains(reservation.state())) {
            throw new IllegalStateException("Reservation has not started");
        }
        if (actualMicros < 0 || actualMicros > reservation.amountMicros()) {
            // Keep the reservation instead of releasing uncertain or underestimated spend.
            throw new IllegalArgumentException("Actual cost must fit the conservative reservation; investigate overage");
        }
        mapper.finish(reservation.periodId(), reservation.amountMicros(), actualMicros, 0);
        mapper.state(id, "SETTLED", actualMicros);
    }

    public List<BudgetMapper.BudgetPeriod> report() { return List.copyOf(mapper.periods()); }
    public Reservation get(String id) { return mapper.find(id); }

    /** Explicit operator reconciliation may exceed an estimate; future reservations still honor the cap. */
    @Transactional
    public void reconcileUncertain(String id, long actualMicros) {
        var reservation = locked(id);
        requireState(reservation, "UNCERTAIN");
        if (actualMicros < 0) throw new IllegalArgumentException("Actual cost cannot be negative");
        mapper.finish(reservation.periodId(), reservation.amountMicros(), actualMicros, 0);
        mapper.state(id, "SETTLED", actualMicros);
    }

    private Reservation locked(String id) {
        requireId(id);
        var existing = mapper.find(id);
        if (existing == null) throw new IllegalArgumentException("Unknown reservation");
        mapper.lockPeriod(existing.periodId());
        return mapper.lockReservation(id);
    }
    private static void requireState(Reservation reservation, String state) {
        if (!state.equals(reservation.state())) throw new IllegalStateException("Unexpected reservation state");
    }
    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 120) throw new IllegalArgumentException("Invalid budget key");
    }
}
