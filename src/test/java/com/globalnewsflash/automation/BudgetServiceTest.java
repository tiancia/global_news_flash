package com.globalnewsflash.automation;

import com.globalnewsflash.automation.application.BudgetService;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:budget-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BudgetServiceTest {
    @Autowired BudgetService budgets;

    @Test void reservationAndSettlementAreIdempotent() {
        String period = UUID.randomUUID().toString(); String id = UUID.randomUUID().toString();
        budgets.configureSyntheticPeriod(period, 100, 2);
        assertThat(budgets.reserve(id, period, 100)).isEqualTo(budgets.reserve(id, period, 100));
        budgets.markStarted(id); budgets.settle(id, 70); budgets.settle(id, 70);
        var report = budgets.report().stream().filter(p -> p.id().equals(period)).findFirst().orElseThrow();
        assertThat(report.spentMicros()).isEqualTo(70);
        assertThat(report.reservedMicros()).isZero();
        assertThat(report.usedRequests()).isEqualTo(1);
        assertThatThrownBy(() -> budgets.settle(id, 60)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void uncertainCostRetainsReservationAndCannotBeCancelled() {
        String period = UUID.randomUUID().toString(); String id = UUID.randomUUID().toString();
        budgets.configureSyntheticPeriod(period, 100, 2); budgets.reserve(id, period, 100);
        budgets.markStarted(id); budgets.markUncertain(id);
        assertThatThrownBy(() -> budgets.reserve(UUID.randomUUID().toString(), period, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> budgets.cancelBeforeCall(id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> budgets.settle(id, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(budgets.report().stream().filter(p -> p.id().equals(period)).findFirst().orElseThrow().reservedMicros()).isEqualTo(100);
    }

    @Test void confirmedNotStartedReservationCanBeReleasedOnce() {
        String period = UUID.randomUUID().toString(); String id = UUID.randomUUID().toString();
        budgets.configureSyntheticPeriod(period, 100, 1); budgets.reserve(id, period, 100);
        budgets.cancelBeforeCall(id); budgets.cancelBeforeCall(id);
        assertThat(budgets.reserve(UUID.randomUUID().toString(), period, 100).state()).isEqualTo("RESERVED");
    }

    @Test void concurrentReservationsCannotCrossBudgetLimit() throws Exception {
        String period = UUID.randomUUID().toString(); budgets.configureSyntheticPeriod(period, 100, 10);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                start.await();
                try { budgets.reserve(UUID.randomUUID().toString(), period, 30); return true; }
                catch (IllegalStateException exhausted) { return false; }
            }));
            start.countDown(); int accepted = 0;
            for (var result : futures) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
        }
    }
}
