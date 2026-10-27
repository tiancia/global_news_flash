package com.globalnewsflash.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReservationTest {
    @Test
    void uncertainSpendKeepsItsReservationUntilReconciled() {
        var reservation = reservation("UNCERTAIN", null);
        assertThatThrownBy(() -> reservation.transitionNeeded("RESERVED", "CANCELLED"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reservation.settlementNeeded(101)).isInstanceOf(IllegalArgumentException.class);
        reservation.requireReconciliation(150);
    }

    @Test
    void settlementIsIdempotentOnlyForTheSameActualCost() {
        var settled = reservation("SETTLED", 30L);
        assertThat(settled.settlementNeeded(30)).isFalse();
        assertThatThrownBy(() -> settled.settlementNeeded(31)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsIncludeReservedAndSpentFundsAndRequestCount() {
        var budget = new BudgetPeriod("day", 100, 3, 40, 50, 2);
        assertThat(budget.canReserve(10)).isTrue();
        assertThat(budget.canReserve(11)).isFalse();
        assertThat(new BudgetPeriod("day", 100, 3, 0, 0, 3).canReserve(0)).isFalse();
        assertThatThrownBy(() -> budget.requireSameLimits(200, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservationKeyCannotBeReusedForAnotherPeriodOrEstimate() {
        var reservation = reservation("RESERVED", null);
        assertThat(reservation.requireSameRequest("day", 100)).isSameAs(reservation);
        assertThatThrownBy(() -> reservation.requireSameRequest("tomorrow", 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservation.requireSameRequest("day", 99)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Reservation reservation(String state, Long actual) {
        return new Reservation("request", "day", 100, actual, state, 0);
    }
}
