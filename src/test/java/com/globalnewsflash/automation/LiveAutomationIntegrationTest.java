package com.globalnewsflash.automation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.globalnewsflash.automation.application.LiveAutomationService;
import com.globalnewsflash.automation.application.LivePipelineService;
import com.globalnewsflash.automation.infrastructure.LiveAutomationMapper;
import com.globalnewsflash.automation.infrastructure.OperatingProperties;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.editorial.domain.*;
import com.globalnewsflash.editorial.port.out.EditorPort;
import com.globalnewsflash.editorial.port.out.ModelPricing;
import com.globalnewsflash.ingestion.application.LiveIngestionService;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.infrastructure.LiveIngestionMapper;
import com.globalnewsflash.ingestion.infrastructure.LiveIngestionProperties;
import com.globalnewsflash.ingestion.port.out.SourceCatalog;
import com.globalnewsflash.ingestion.port.out.SourceCollector;
import com.globalnewsflash.integrations.telegram.TelegramProperties;
import com.globalnewsflash.publication.application.PublicationService;
import com.globalnewsflash.publication.domain.DeliveryResult;
import com.globalnewsflash.publication.port.out.DeliveryGateway;
import com.globalnewsflash.shared.port.out.SnapshotCodec;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Real LIVE application services and SQL, with all outbound I/O replaced at the ports. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:live-regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "news.runtime.mode=LIVE", "news.runtime.external-enabled=true",
        "news.automation.policy-ready=true", "news.automation.batch-size=1",
        "news.ingestion.enabled=true", "news.ingestion.max-items-per-source=3",
        "news.ingestion.usgs.enabled=true", "news.ingestion.usgs.approved=true",
        "news.ingestion.usgs.license-version=test-v1", "news.ingestion.usgs.permitted-producers=us"})
@ActiveProfiles("test")
class LiveAutomationIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");
    @Autowired LiveAutomationService automation;
    @Autowired LivePipelineService pipeline;
    @Autowired LiveIngestionService ingestion;
    @Autowired LiveIngestionMapper inbox;
    @Autowired LiveAutomationMapper jobs;
    @Autowired PublicationService publications;
    @Autowired TelegramProperties telegram;
    @Autowired OperatingProperties operating;
    @Autowired LiveIngestionProperties ingestionSettings;
    @Autowired RuntimeProperties runtime;
    @Autowired SnapshotCodec json;
    @Autowired SourceCatalog catalog;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SourceCollector collector;
    @MockitoBean EditorPort editor;
    @MockitoBean ModelPricing pricing;
    @MockitoBean DeliveryGateway delivery;
    @MockitoBean Clock clock;
    final AtomicReference<Instant> now = new AtomicReference<>();

    @BeforeEach void setup() {
        for (String table : List.of("publication_alert", "publication_resolution", "publication_attempt",
                "publication_operation", "publication_channel_limit", "live_editor_job", "news_event_version",
                "news_event", "news_material", "budget_reservation", "budget_period", "ingestion_inbox",
                "ingestion_article_failure", "automation_run", "automation_alert")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE ingestion_source_state SET next_poll_at=0,lease_owner=NULL,lease_until=0,snapshot_payload=NULL,snapshot_offset=0,snapshot_created_at=NULL");
        jdbc.update("UPDATE automation_lease SET owner=NULL,lease_until=0");
        reset(collector, editor, pricing, delivery, clock);
        now.set(NOW);
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.millis()).thenAnswer(i -> now.get().toEpochMilli());
        when(pricing.readinessReasons()).thenReturn(List.of());
        when(pricing.estimatedRequestMicros()).thenReturn(10_000L);
        when(pricing.actualMicros(any())).thenReturn(100L);
        when(pricing.getTimeout()).thenReturn(Duration.ofSeconds(45));
        when(editor.supports(false)).thenReturn(true);
        when(editor.livePromptVersion()).thenReturn("grounded-english-news-v1");
        when(editor.edit(any())).thenAnswer(i -> response(i.getArgument(0), false));
        when(delivery.deliver(any())).thenReturn(DeliveryResult.success("123"));
        telegram.setEnabled(true);
        telegram.setBotToken("123456:" + "x".repeat(30));
        telegram.setChatId("-1001234567890");
        operating.setEnabled(false); // no background scheduler is installed in this test context
        operating.setDailyRequests(100);
        publications.setPaused(false);
        when(collector.open(any(), any(), any(), any(), any())).thenReturn(new SourceCollector.Snapshot("feed", "etag", null, false));
        when(collector.collect(any(), any(), any(), anyInt(), any(), any())).thenReturn(batch(List.of(material()), List.of()));
    }

    @Test void liveCyclePersistsAndPublishesWithoutDuplicateModelOrDeliveryCalls() {
        assertThat(automation.runOnce()).containsEntry("state", "COMPLETED");
        assertThat(publications.list()).singleElement().satisfies(op -> assertThat(op.state()).isEqualTo("SENT"));
        now.set(NOW.plusSeconds(301));
        automation.runOnce();
        verify(editor, times(1)).edit(any());
        verify(delivery, times(1)).deliver(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE state='SETTLED'", Integer.class)).isEqualTo(1);
    }

    @Test void missingTelegramTokenStopsBeforeCollectionAndModelSpend() {
        telegram.setBotToken("");
        assertThat(automation.status()).containsEntry("editingReady", true).containsEntry("publicationReady", false)
                .containsEntry("ready", false).containsEntry("running", false);
        assertThat(automation.runOnce()).containsEntry("state", "NOT_READY");
        verifyNoInteractions(collector, editor, delivery);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Integer.class)).isZero();
    }

    @Test void previewModeAndPausedSchedulerHaveExplicitStatus() {
        telegram.setEnabled(false);
        telegram.setBotToken("");
        assertThat(automation.status()).containsEntry("editingReady", true).containsEntry("publicationReady", false)
                .containsEntry("ready", false);
        assertThat(automation.runOnce()).containsEntry("state", "COMPLETED");
        assertThat(publications.list()).singleElement().satisfies(op -> assertThat(op.state()).isEqualTo("PENDING"));
        verifyNoInteractions(delivery);
        telegram.setEnabled(true);
        telegram.setBotToken("123456:" + "x".repeat(30));
        operating.setEnabled(true);
        publications.setPaused(true);
        assertThat(automation.status()).containsEntry("ready", true).containsEntry("running", false);
        publications.setPaused(false);
        assertThat(automation.status()).containsEntry("running", true);
    }

    @Test void heldFormatFailureCanBeExplicitlyRequeuedAndChargedOncePerAttempt() {
        doAnswer(i -> response(i.getArgument(0), true)).when(editor).edit(any());
        automation.runOnce();
        var item = ingestion.inboxStatus().getFirst();
        assertThat(item.state()).isEqualTo("HELD");
        automation.runOnce();
        verify(editor, times(1)).edit(any());
        doAnswer(i -> response(i.getArgument(0), false)).when(editor).edit(any());
        automation.requeue(item.id());
        automation.runOnce();
        verify(editor, times(2)).edit(any());
        verify(delivery, times(1)).deliver(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE state='SETTLED'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(spent_micros) FROM budget_period", Long.class)).isEqualTo(200L);
    }

    @Test void explicitEditorRetryCanRepairHeldDraftButCannotRetryEligibleJob() {
        doAnswer(i -> response(i.getArgument(0), true)).when(editor).edit(any());
        automation.runOnce();
        String id = jobs.jobs().getFirst().id();
        doAnswer(i -> response(i.getArgument(0), false)).when(editor).edit(any());
        assertThat(pipeline.retryJob(id).state()).isEqualTo("ELIGIBLE");
        assertThatThrownBy(() -> pipeline.retryJob(id)).isInstanceOf(IllegalStateException.class);
        verify(editor, times(2)).edit(any());
    }

    @Test void staleHeldRetryRollsBackInboxRequeueAndDoesNotSpend() {
        doAnswer(i -> response(i.getArgument(0), true)).when(editor).edit(any());
        automation.runOnce();
        var item = ingestion.inboxStatus().getFirst();
        now.set(NOW.plus(Duration.ofHours(13)));
        assertThatThrownBy(() -> automation.requeue(item.id())).hasMessage("EDITOR_EVIDENCE_NOT_ELIGIBLE");
        assertThat(ingestion.inboxStatus().getFirst().state()).isEqualTo("HELD");
        verify(editor, times(1)).edit(any());
    }

    @Test void uncertainModelCallCannotBeRetriedUntilAccounted() {
        doThrow(new EditorFailure("EDITOR_FAILED", true)).when(editor).edit(any());
        automation.runOnce();
        var job = jobs.jobs().getFirst();
        assertThat(job.state()).isEqualTo("UNCERTAIN");
        assertThatThrownBy(() -> pipeline.retryJob(job.id())).hasMessage("EDITOR_JOB_NOT_RETRYABLE");
        verify(editor, times(1)).edit(any());
        verifyNoInteractions(delivery);
    }

    @Test void heldRetryStillHonorsDailyRequestBudget() {
        operating.setDailyRequests(1);
        doAnswer(i -> response(i.getArgument(0), true)).when(editor).edit(any());
        automation.runOnce();
        assertThat(pipeline.retryJob(jobs.jobs().getFirst().id()).state()).isEqualTo("DEFERRED");
        verify(editor, times(1)).edit(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation", Integer.class)).isEqualTo(1);
    }

    @Test void reviewRequiredDraftCannotBeManuallyRegeneratedThroughFormatRetry() {
        doThrow(new EditorFailure("EDITOR_RISK_REQUIRES_REVIEW", false, new EditorResponse.Usage(10, 10, false)))
                .when(editor).edit(any());
        automation.runOnce();
        assertThatThrownBy(() -> automation.requeue(ingestion.inboxStatus().getFirst().id())).hasMessage("EDITOR_JOB_NOT_RETRYABLE");
        assertThat(ingestion.inboxStatus().getFirst().state()).isEqualTo("HELD");
        verify(editor, times(1)).edit(any());
    }

    @Test void concurrentRetryCannotDuplicateModelCallAndExpiredJobKeepsBudgetUncertain() throws Exception {
        doAnswer(i -> response(i.getArgument(0), true)).when(editor).edit(any());
        automation.runOnce();
        String id = jobs.jobs().getFirst().id();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(i -> {
            entered.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return response(i.getArgument(0), false);
        }).when(editor).edit(any());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> pipeline.retryJob(id));
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> pipeline.retryJob(id)).hasMessage("EDITOR_JOB_NOT_RETRYABLE");
                now.set(NOW.plus(Duration.ofMinutes(4)));
                assertThat(pipeline.recoverExpired()).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("UNCERTAIN");
        }
        verify(editor, times(2)).edit(any());
        verifyNoInteractions(delivery);
        assertThat(jobs.findJob(id).state()).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE state='UNCERTAIN'", Integer.class)).isEqualTo(1);
    }

    @Test void perArticleFailuresPersistAcrossWorkerRestartAndStopBlockingAfterThreeAttempts() {
        var failure = new SourceCollector.ItemFailure("b".repeat(64), "ARTICLE_BODY_NOT_FOUND");
        when(collector.collect(any(), any(), any(), anyInt(), any(), any())).thenReturn(batch(List.of(material()), List.of(failure)));
        assertThat(ingestion.poll().stored()).isEqualTo(1);
        var restarted = new LiveIngestionService(ingestionSettings, runtime, inbox, json, clock, transactions, collector, catalog);
        now.set(NOW.plusSeconds(61));
        restarted.poll();
        now.set(NOW.plusSeconds(122));
        restarted.poll();
        assertThat(inbox.recentInbox()).hasSize(1);
        assertThat(inbox.articleFailures()).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo("QUARANTINED");
            assertThat(row.attempts()).isEqualTo(3);
        });
        assertThat(inbox.lockSourceState("usgs").snapshotPayload()).isNull();
        verify(collector, times(1)).open(any(), any(), any(), any(), any());
    }

    @Test void recoveredArticleClearsRetryAndHeartbeatLossDoesNotConsumeAttempt() {
        var failure = new SourceCollector.ItemFailure(com.globalnewsflash.shared.Hashing.sha256(material().externalId()), "SOURCE_TIMEOUT");
        when(collector.collect(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(batch(List.of(), List.of(failure)))
                .thenReturn(batch(List.of(material()), List.of()));
        ingestion.poll();
        now.set(NOW.plusSeconds(61));
        ingestion.poll();
        assertThat(inbox.articleFailures().getFirst().state()).isEqualTo("RECOVERED");
        assertThat(inbox.lockSourceState("usgs").snapshotPayload()).isNull();
        assertThatThrownBy(() -> ingestion.poll(() -> { throw new IllegalStateException(); }))
                .isInstanceOf(SourceCollector.HeartbeatFailure.class);
        assertThat(inbox.articleFailures().getFirst().attempts()).isEqualTo(1);
    }

    private SourceCollector.Batch batch(List<SourceMaterial> materials, List<SourceCollector.ItemFailure> failures) {
        return new SourceCollector.Batch(materials, "etag", null, "cursor", false, 2, true, failures);
    }
    private SourceMaterial material() {
        return new SourceMaterial("usgs", "event1", "usgs:event1", "https://earthquake.usgs.gov/earthquakes/event1",
                "Earthquake report", "EARTHQUAKE", "us", "usgs:event1", NOW.minusSeconds(60), null, null, NOW,
                List.of(new SourceMaterial.Fact("magnitude", "USGS reports an earthquake with magnitude 5.")), false, false);
    }
    private EditorResponse response(EditorRequest request, boolean duplicate) {
        var fact = request.facts().getFirst();
        var statement = new EditorResponse.Statement(fact.sourceId(), fact.materialId(), fact.factId(), fact.text(), fact.text());
        return new EditorResponse(duplicate ? List.of(statement, statement) : List.of(statement), "openai", "test-model",
                request.promptVersion(), request.contractVersion(), new EditorResponse.Usage(10, 10, false), false, "LOW", true);
    }
}
