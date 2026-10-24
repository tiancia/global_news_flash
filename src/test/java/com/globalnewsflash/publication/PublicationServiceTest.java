package com.globalnewsflash.publication;

import com.globalnewsflash.publication.application.DeliveryGateway;
import com.globalnewsflash.publication.application.DeliveryResult;
import com.globalnewsflash.publication.application.PublicationService;
import com.globalnewsflash.publication.application.PublicationGuard;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.infrastructure.PublicationMapper;
import com.globalnewsflash.publication.infrastructure.PublicationProperties;
import com.globalnewsflash.publication.infrastructure.PublicationWorkerConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:publication-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "news.evidence-guard.enabled=false"})
@ActiveProfiles("test")
class PublicationServiceTest {
    @Autowired PublicationService service;
    @Autowired PublicationMapper mapper;
    @Autowired PublicationProperties properties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DeliveryGateway gateway;
    @MockitoBean Clock clock;
    @MockitoBean PublicationGuard guard;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM publication_alert");
        jdbc.update("DELETE FROM publication_resolution");
        jdbc.update("DELETE FROM publication_attempt");
        // Derived operations must be deleted before their referenced originals.
        jdbc.update("DELETE FROM publication_operation WHERE original_operation_id IS NOT NULL");
        jdbc.update("DELETE FROM publication_operation");
        jdbc.update("UPDATE publication_control SET paused=TRUE WHERE id=1");
        jdbc.update("DELETE FROM publication_control_audit");
        reset(gateway, clock, guard);
        now.set(Instant.parse("2026-10-24T12:00:00Z"));
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(guard.rejectionReasons(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            PublicationOperation operation = invocation.getArgument(0);
            return DeliveryResult.success("sim-" + operation.id());
        }).when(gateway).deliver(any());
    }

    @Test
    void pauseIsPersistentAndDefaultDeliveryDoesNotRun() {
        PublicationOperation operation = enqueue("event-1");
        assertThat(service.isPaused()).isTrue();
        assertThat(service.dispatch(operation.id()).state()).isEqualTo("PENDING");
        verifyNoInteractions(gateway);
        service.setPaused(false);
        PublicationService rebuilt = rebuild();
        assertThat(rebuilt.isPaused()).isFalse();
        assertThat(rebuilt.dispatch(operation.id()).state()).isEqualTo("SENT");
        service.setPaused(true);
        assertThat(rebuilt.isPaused()).isTrue();
    }

    @Test
    void sameImmutableVersionIsIdempotentButDifferentContentIsRejected() {
        PublicationOperation first = enqueue("event-1");
        assertThat(enqueue("event-1").id()).isEqualTo(first.id());
        assertThat(service.list()).hasSize(1);
        assertThatThrownBy(() -> service.enqueue("event-1", 1, "offline-channel", "SEND",
                "Different synthetic content", null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.enqueue("real", 1, "channel", "SEND", "real", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void successfulDeliveryAndAttemptSurviveServiceReconstruction() {
        PublicationOperation operation = enqueue("event-1");
        service.setPaused(false);
        PublicationOperation sent = service.dispatch(operation.id());
        assertThat(sent.state()).isEqualTo("SENT");
        assertThat(sent.messageId()).startsWith("sim-");
        assertThat(sent.attempts()).isEqualTo(1);
        assertThat(service.attempts(operation.id())).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo("SUCCESS"));
        assertThat(rebuild().dispatch(operation.id()).state()).isEqualTo("SENT");
        verify(gateway, times(1)).deliver(any());
    }

    @Test
    void gatewayExceptionIsUnknownAndNeverAutomaticallyRetried() {
        doThrow(new IllegalStateException("secret must not be persisted")).when(gateway).deliver(any());
        PublicationOperation operation = enqueue("timeout");
        service.setPaused(false);
        assertThat(service.dispatch(operation.id()).state()).isEqualTo("UNKNOWN");
        assertThat(service.get(operation.id()).lastError()).doesNotContain("secret");
        now.set(now.get().plus(Duration.ofHours(1)));
        assertThat(service.recoverExpired()).isZero();
        assertThat(rebuild().dispatch(operation.id()).state()).isEqualTo("UNKNOWN");
        verify(gateway, times(1)).deliver(any());
    }

    @Test
    void explicitUnknownAndMissingSuccessIdAlsoFreezeTheOperation() {
        service.setPaused(false);
        doReturn(DeliveryResult.unknown("NETWORK_TIMEOUT")).when(gateway).deliver(any());
        PublicationOperation first = enqueue("unknown");
        assertThat(service.dispatch(first.id()).state()).isEqualTo("UNKNOWN");
        doReturn(DeliveryResult.success(null)).when(gateway).deliver(any());
        PublicationOperation second = enqueue("missing-id");
        assertThat(service.dispatch(second.id()).lastError()).isEqualTo("MISSING_MESSAGE_ID");
        assertThat(service.get(second.id()).state()).isEqualTo("UNKNOWN");
    }

    @Test
    void rateLimitWaitsForRetryAfterAndStopsAtAttemptLimit() {
        doReturn(DeliveryResult.rateLimited(Duration.ofSeconds(30))).when(gateway).deliver(any());
        PublicationOperation operation = enqueue("limited");
        service.setPaused(false);
        PublicationOperation waiting = service.dispatch(operation.id());
        assertThat(waiting.state()).isEqualTo("RETRY_WAIT");
        assertThat(waiting.nextAttemptAt()).isEqualTo(now.get().plusSeconds(30));
        assertThat(service.dispatch(operation.id()).attempts()).isEqualTo(1);
        for (int attempt = 2; attempt <= properties.getMaxAttempts(); attempt++) {
            now.set(now.get().plusSeconds(30));
            service.dispatch(operation.id());
        }
        assertThat(service.get(operation.id()).state()).isEqualTo("FAILED");
        assertThat(service.get(operation.id()).lastError()).isEqualTo("RETRY_LIMIT_REACHED");
        assertThat(service.attempts(operation.id())).hasSize(properties.getMaxAttempts());
        service.dispatch(operation.id());
        verify(gateway, times(properties.getMaxAttempts())).deliver(any());
    }

    @Test
    void expiredClaimCanBeRecoveredWithoutPretendingToHaveSent() {
        PublicationOperation operation = enqueue("claimed");
        assertThat(mapper.claim(operation.id(), "test-token", now.get().plusSeconds(5), now.get())).isEqualTo(1);
        now.set(now.get().plusSeconds(6));
        assertThat(service.recoverExpired()).isEqualTo(1);
        assertThat(service.get(operation.id()).state()).isEqualTo("PENDING");
        assertThat(service.get(operation.id()).attempts()).isZero();
        assertThat(service.attempts(operation.id())).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void recoveryOfSendingAndLateSuccessCannotCauseAnotherSend() {
        PublicationOperation operation = enqueue("crash-window");
        service.setPaused(false);
        doAnswer(invocation -> {
            // Simulate losing the worker while the request may already have reached its target.
            now.set(now.get().plus(properties.getLease()).plusSeconds(1));
            assertThat(service.recoverExpired()).isEqualTo(1);
            assertThat(service.get(operation.id()).state()).isEqualTo("UNKNOWN");
            return DeliveryResult.success("sim-late-result");
        }).when(gateway).deliver(any());
        assertThat(service.dispatch(operation.id()).state()).isEqualTo("UNKNOWN");
        assertThat(service.get(operation.id()).messageId()).isNull();
        assertThat(service.attempts(operation.id())).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo("LATE_SUCCESS"));
        assertThat(rebuild().dispatch(operation.id()).state()).isEqualTo("UNKNOWN");
        verify(gateway, times(1)).deliver(any());
    }

    @Test
    void leaseExpiryBeforeResultCommitIsConservativelyUnknownEvenWithoutRecoveryWorker() {
        PublicationOperation operation = enqueue("expired-result");
        service.setPaused(false);
        doAnswer(invocation -> {
            now.set(now.get().plus(properties.getLease()).plusSeconds(1));
            return DeliveryResult.success("sim-expired");
        }).when(gateway).deliver(any());
        assertThat(service.dispatch(operation.id()).state()).isEqualTo("UNKNOWN");
        assertThat(service.get(operation.id()).lastError()).isEqualTo("LEASE_EXPIRED_RESULT_UNKNOWN");
    }

    @Test
    void concurrentDispatchAllowsOnlyOneGatewayInvocation() throws Exception {
        PublicationOperation operation = enqueue("concurrent");
        service.setPaused(false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release gateway");
            }
            return DeliveryResult.success("sim-concurrent");
        }).when(gateway).deliver(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.dispatch(operation.id()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> service.dispatch(operation.id()));
                assertThat(second.get(5, TimeUnit.SECONDS).state()).isEqualTo("SENDING");
            } finally {
                finish.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo("SENT");
        }
        assertThat(service.attempts(operation.id())).hasSize(1);
        verify(gateway, times(1)).deliver(any());
    }

    @Test
    void editAndCorrectionHaveSeparateHistoryAndRequireSentOriginal() {
        PublicationOperation original = enqueue("update-event");
        assertThatThrownBy(() -> service.enqueue("update-event", 2, "offline-channel", "EDIT",
                "Synthetic edit", original.id(), true)).isInstanceOf(IllegalArgumentException.class);
        service.setPaused(false);
        PublicationOperation sent = service.dispatch(original.id());
        PublicationOperation edit = service.enqueue("update-event", 2, "offline-channel", "EDIT",
                "Synthetic edit", original.id(), true);
        PublicationOperation edited = service.dispatch(edit.id());
        assertThat(edited.messageId()).isEqualTo(sent.messageId());
        PublicationOperation correction = service.enqueue("update-event", 3, "offline-channel", "CORRECTION",
                "Synthetic public correction", original.id(), true);
        assertThat(service.dispatch(correction.id()).messageId()).isNotEqualTo(sent.messageId());
        assertThat(service.get(original.id()).body()).isEqualTo("Synthetic evidence-backed fixture body");
        assertThat(service.list()).hasSize(3);
    }

    @Test
    void revokedEligibilityCancelsBeforeSendingAndGuardFailureAlsoStopsDelivery() {
        service.setPaused(false);
        when(guard.rejectionReasons(any())).thenReturn(List.of("SOURCE_DISABLED"));
        PublicationOperation revoked = enqueue("revoked");
        assertThat(service.dispatch(revoked.id()).state()).isEqualTo("CANCELLED");
        assertThat(service.get(revoked.id()).lastError()).isEqualTo("ELIGIBILITY_REVOKED");
        assertThat(service.attempts(revoked.id())).isEmpty();
        assertThat(service.alerts()).singleElement()
                .satisfies(alert -> assertThat(alert.reasonCode()).isEqualTo("SOURCE_DISABLED"));
        when(guard.rejectionReasons(any())).thenThrow(new IllegalStateException("unavailable"));
        PublicationOperation unavailable = enqueue("guard-unavailable");
        assertThat(service.dispatch(unavailable.id()).state()).isEqualTo("CANCELLED");
        assertThat(service.alerts()).hasSize(2).anySatisfy(alert ->
                assertThat(alert.reasonCode()).isEqualTo("GUARD_CHECK_FAILED"));
        verifyNoInteractions(gateway);
    }

    @Test
    void enqueueParticipatesInOuterTransactionAndPauseHasDurableAudit() {
        var outer = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            enqueue("rolled-back");
            status.setRollbackOnly();
        });
        assertThat(service.list()).isEmpty();
        service.setPaused(false);
        service.setPaused(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication_control_audit", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void confirmedSentReconciliationKeepsAttemptHistoryAndAcknowledgesPersistentAlert() {
        PublicationOperation operation = makeUnknown("reconcile-sent");
        assertThat(service.alerts()).singleElement()
                .satisfies(alert -> assertThat(alert.acknowledgedAt()).isNull());
        PublicationOperation resolved = service.resolveUnknown(operation.id(), "CONFIRMED_SENT",
                "sim-manually-verified", "Checked the synthetic delivery journal");
        assertThat(resolved.state()).isEqualTo("SENT");
        assertThat(resolved.messageId()).isEqualTo("sim-manually-verified");
        assertThat(service.attempts(operation.id())).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo("UNKNOWN"));
        assertThat(service.resolutions(operation.id())).singleElement()
                .satisfies(resolution -> assertThat(resolution.note()).contains("synthetic"));
        assertThat(service.alerts()).singleElement()
                .satisfies(alert -> assertThat(alert.acknowledgedAt()).isEqualTo(now.get()));
        assertThat(service.dispatch(operation.id()).state()).isEqualTo("SENT");
        verify(gateway, times(1)).deliver(any());
    }

    @Test
    void confirmedNotSentTerminatesWithoutAutomaticallyReenqueuing() {
        PublicationOperation operation = makeUnknown("reconcile-not-sent");
        PublicationOperation resolved = service.resolveUnknown(operation.id(), "CONFIRMED_NOT_SENT",
                null, "Verified no synthetic message was delivered");
        assertThat(resolved.state()).isEqualTo("FAILED");
        assertThat(resolved.messageId()).isNull();
        assertThat(service.processDueBatch()).isZero();
        assertThat(service.list()).hasSize(1);
        assertThat(service.alerts()).hasSize(2).allSatisfy(alert ->
                assertThat(alert.acknowledgedAt()).isNotNull());
        assertThat(service.attempts(operation.id())).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo("UNKNOWN"));
        verify(gateway, times(1)).deliver(any());
    }

    @Test
    void reconciliationRequiresUnknownEvidenceAndIdempotentlyKeepsTheOriginalAudit() {
        PublicationOperation pending = enqueue("not-unknown");
        assertThatThrownBy(() -> service.resolveUnknown(pending.id(), "CONFIRMED_NOT_SENT", null, "checked"))
                .isInstanceOf(IllegalArgumentException.class);
        PublicationOperation operation = makeUnknown("reconcile-validation");
        assertThatThrownBy(() -> service.resolveUnknown(operation.id(), "CONFIRMED_SENT", null, "checked"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveUnknown(operation.id(), "CONFIRMED_NOT_SENT", null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveUnknown(operation.id(), "RETRY", null, "checked"))
                .isInstanceOf(IllegalArgumentException.class);
        service.resolveUnknown(operation.id(), "CONFIRMED_SENT", "sim-verified", "Original verification");
        assertThat(service.resolveUnknown(operation.id(), "CONFIRMED_SENT", "sim-verified", "Retry request")
                .state()).isEqualTo("SENT");
        assertThat(service.resolutions(operation.id())).singleElement()
                .satisfies(resolution -> assertThat(resolution.note()).isEqualTo("Original verification"));
        assertThatThrownBy(() -> service.resolveUnknown(operation.id(), "CONFIRMED_NOT_SENT", null, "contradiction"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveUnknown(operation.id(), "CONFIRMED_SENT", "other-id", "contradiction"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentIdenticalReconciliationCreatesOneAuditRecord() throws Exception {
        PublicationOperation operation = makeUnknown("reconcile-concurrent");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.resolveUnknown(operation.id(), "CONFIRMED_SENT",
                    "sim-reconciled", "Verified by local operator"));
            var second = executor.submit(() -> service.resolveUnknown(operation.id(), "CONFIRMED_SENT",
                    "sim-reconciled", "Verified by local operator"));
            assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo("SENT");
            assertThat(second.get(5, TimeUnit.SECONDS).state()).isEqualTo("SENT");
        }
        assertThat(service.resolutions(operation.id())).hasSize(1);
        verify(gateway, times(1)).deliver(any());
    }

    @Test
    void dueWorkerIsBoundedAndUnknownDeliveryDoesNotBlockOtherOperations() {
        for (int i = 0; i < 23; i++) {
            enqueue("batch-" + i);
        }
        assertThat(service.processDueBatch()).isZero();
        service.setPaused(false);
        doAnswer(invocation -> {
            PublicationOperation operation = invocation.getArgument(0);
            if (operation.eventKey().equals("batch-0")) {
                throw new IllegalStateException("Unknown synthetic delivery");
            }
            return DeliveryResult.success("sim-" + operation.id());
        }).when(gateway).deliver(any());
        assertThat(service.processDueBatch()).isEqualTo(20);
        assertThat(service.processDueBatch()).isEqualTo(3);
        assertThat(service.list().stream().filter(op -> "SENT".equals(op.state()))).hasSize(22);
        assertThat(service.alerts()).singleElement()
                .satisfies(alert -> assertThat(alert.category()).isEqualTo("UNKNOWN"));
        verify(gateway, times(23)).deliver(any());
    }

    @Test
    void workerDispatchErrorsArePersistedDeduplicatedAndIsolated() {
        PublicationOperation failing = enqueue("worker-failing");
        PublicationOperation healthy = enqueue("worker-healthy");
        service.setPaused(false);
        PublicationService workerService = spy(rebuild());
        doThrow(new IllegalStateException("sensitive error must not be stored"))
                .when(workerService).dispatch(failing.id());
        assertThat(workerService.processDueBatch()).isEqualTo(1);
        assertThat(service.get(healthy.id()).state()).isEqualTo("SENT");
        assertThat(service.get(failing.id()).state()).isEqualTo("PENDING");
        assertThat(service.alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.category()).isEqualTo("WORKER_ERROR");
            assertThat(alert.reasonCode()).isEqualTo("WORKER_DISPATCH_EXCEPTION");
        });
        assertThat(workerService.processDueBatch()).isZero();
        assertThat(service.alerts()).hasSize(1);
    }

    @Test
    void schedulingIsRegisteredOnlyWhenExplicitlyEnabled() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(PublicationWorkerConfiguration.class)
                .withBean(PublicationService.class, () -> mock(PublicationService.class));
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(PublicationWorkerConfiguration.PublicationWorker.class));
        runner.withPropertyValues("news.publication.worker-enabled=true",
                        "news.publication.worker-delay-ms=60000")
                .run(context -> assertThat(context)
                        .hasSingleBean(PublicationWorkerConfiguration.PublicationWorker.class));
    }

    private PublicationOperation makeUnknown(String eventKey) {
        doReturn(DeliveryResult.unknown("NETWORK_TIMEOUT")).when(gateway).deliver(any());
        PublicationOperation operation = enqueue(eventKey);
        service.setPaused(false);
        return service.dispatch(operation.id());
    }

    private PublicationOperation enqueue(String eventKey) {
        return service.enqueue(eventKey, 1, "offline-channel", "SEND",
                "Synthetic evidence-backed fixture body", null, true);
    }

    private PublicationService rebuild() {
        return new PublicationService(mapper, gateway, clock, properties, transactionManager, List.of(guard));
    }
}
