package com.globalnewsflash.automation.port.in;

import com.globalnewsflash.automation.domain.BudgetPeriod;
import com.globalnewsflash.automation.domain.Reservation;
import java.util.List;

/** Public use cases of the automation module. */
public interface BudgetUseCase {

    void configureSyntheticPeriod(String id, long maxMicros, int maxRequests);
    void configurePeriod(String id, long maxMicros, int maxRequests);
    Reservation reserve(String id, String periodId, long conservativeMicros);
    Reservation tryReserve(String id, String periodId, long conservativeMicros);
    void markStarted(String id);
    void markUncertain(String id);
    void cancelBeforeCall(String id);
    void settle(String id, long actualMicros);
    List<BudgetPeriod> report();
    Reservation get(String id);
    void reconcileUncertain(String id, long actualMicros);
}
