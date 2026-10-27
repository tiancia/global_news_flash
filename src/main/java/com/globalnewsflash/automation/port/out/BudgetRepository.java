package com.globalnewsflash.automation.port.out;

import com.globalnewsflash.automation.domain.BudgetPeriod;
import com.globalnewsflash.automation.domain.Reservation;
import java.util.List;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface BudgetRepository {
    BudgetPeriod lockPeriod(String id);
    List<BudgetPeriod> periods();
    Reservation find(String id);
    Reservation lockReservation(String id);
    void create(String id, long micros, int requests);
    void insert(Reservation reservation);
    void reserve(String id, long amount);
    void finish(String id, long reserved, long actual, int refundRequest);
    void state(String id, String state, Long actual);
}
