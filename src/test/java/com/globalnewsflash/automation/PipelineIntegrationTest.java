package com.globalnewsflash.automation;

import com.globalnewsflash.automation.application.PipelineService;
import com.globalnewsflash.automation.application.BudgetService;
import com.globalnewsflash.automation.infrastructure.PipelineMapper;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.integrations.sources.UsgsAdapter;
import com.globalnewsflash.publication.application.PublicationService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pipeline-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(PipelineIntegrationTest.TimeConfiguration.class)
class PipelineIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-24T14:00:00Z");
    @Autowired PipelineService pipeline;
    @Autowired PublicationService publications;
    @Autowired UsgsAdapter usgs;
    @Autowired MutableClock clock;
    @Autowired PipelineMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BudgetService budgets;

    @BeforeEach void reset() { clock.current.set(NOW); pipeline.setSourceEnabled("usgs:synthetic", true); publications.setPaused(true);
        budgets.configureSyntheticPeriod("integration-period", 1000000, 1000); }
    @AfterEach void pause() { publications.setPaused(true); }

    @Test void completeFlowIsPersistentAndRediscoveryDoesNotDuplicate() throws Exception {
        var material = material();
        var first = process(List.of(material), policy());
        assertThat(first.state()).isEqualTo("ELIGIBLE");
        assertThat(publications.dispatch(first.publicationId()).state()).isEqualTo("PENDING");
        publications.setPaused(false);
        assertThat(publications.dispatch(first.publicationId()).messageId()).startsWith("sim-");
        var laterDiscovery = copy(material, material.eventKey(), material.occurredAt(), NOW.plusSeconds(3600));
        var duplicate = process(List.of(laterDiscovery), policy());
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.eventVersion()).isEqualTo(first.eventVersion());
        assertThat(duplicate.publicationId()).isEqualTo(first.publicationId());
        assertThat(publications.attempts(first.publicationId())).hasSize(1);
    }

    @Test void equivalentInputAndPolicySetOrderHaveStableIdentity() throws Exception {
        var material = material();
        var first = process(List.of(material), policy());
        var reordered = new EditorialPolicy("integration-v1", Duration.ofHours(48), 2000, 1,
                new LinkedHashSet<>(List.of("OFFICIAL_ANNOUNCEMENT", "EARTHQUAKE")), true);
        var second = process(List.of(material, material), reordered);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.publicationId()).isEqualTo(first.publicationId());
    }

    @Test void disabledSourceStopsAdmissionBeforeMaterialStorageAndCancelsQueuedTask() throws Exception {
        var first = process(List.of(material()), policy());
        long count = pipeline.materialCount();
        pipeline.setSourceEnabled("usgs:synthetic", false);
        var denied = process(List.of(material()), policy());
        assertThat(denied.reasons()).contains("SOURCE_DISABLED");
        assertThat(pipeline.materialCount()).isEqualTo(count);
        publications.setPaused(false);
        assertThat(publications.dispatch(first.publicationId()).state()).isEqualTo("CANCELLED");
        assertThat(publications.attempts(first.publicationId())).isEmpty();
    }

    @Test void staleAtDispatchCannotUsePreviouslyEligibleDecision() throws Exception {
        var first = process(List.of(material()), policy());
        clock.current.set(NOW.plus(Duration.ofDays(3)));
        publications.setPaused(false);
        assertThat(publications.dispatch(first.publicationId()).state()).isEqualTo("CANCELLED");
    }

    @Test void revisionIsStoredButWaitsForAnExplicitAmendmentPolicy() throws Exception {
        var firstMaterial = material();
        var first = process(List.of(firstMaterial), policy());
        var revised = usgs.parse(fixture("usgs-revised.json"), NOW, true).getFirst();
        revised = copy(revised, firstMaterial.eventKey(), revised.occurredAt(), NOW);
        var second = process(List.of(revised), policy());
        assertThat(second.eventVersion()).isEqualTo(2);
        assertThat(second.reasons()).contains("REVISION_POLICY_NOT_READY");
        assertThat(second.publicationId()).isNull();
        publications.setPaused(false);
        assertThat(publications.dispatch(first.publicationId()).state()).isEqualTo("CANCELLED");
    }

    @Test void pipelineAndPublicationQueueRollbackTogether() throws Exception {
        var material = material();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            process(List.of(material), policy());
            throw new IllegalStateException("injected rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.latestVersion(material.eventKey())).isNull();
        assertThat(publications.list()).noneMatch(o -> o.eventKey().equals(material.eventKey()));
    }

    @Test void concurrentProcessingCreatesOneVersionAndOneTask() throws Exception {
        var material = material();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<PipelineService.PipelineRun> task = () -> { gate.await(); return process(List.of(material), policy()); };
            var a = pool.submit(task); var b = pool.submit(task); gate.countDown();
            var resultA = a.get(10, TimeUnit.SECONDS); var resultB = b.get(10, TimeUnit.SECONDS);
            assertThat(resultA.publicationId()).isEqualTo(resultB.publicationId());
            assertThat(List.of(resultA.duplicate(), resultB.duplicate())).containsExactlyInAnyOrder(false, true);
        }
    }

    @Test void newDiscoveryTimeDoesNotReviveOldEvent() throws Exception {
        var material = material();
        var old = copy(material, material.eventKey(), NOW.minus(Duration.ofDays(10)), NOW);
        var result = process(List.of(old), policy());
        assertThat(result.state()).isEqualTo("EXPIRED");
        assertThat(result.publicationId()).isNull();
    }

    @Test void unconfiguredPolicyIsHeldAndRealSourceCannotBeEnabled() throws Exception {
        var result = process(List.of(material()), new EditorialPolicy("pending", null, 0, 1, Set.of(), false));
        assertThat(result.reasons()).contains("POLICY_NOT_READY");
        assertThatThrownBy(() -> pipeline.setSourceEnabled("usgs:real", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void heldEventCanBecomeItsFirstPublishableVersionAfterPolicyIsConfigured() throws Exception {
        var material = material();
        var held = process(List.of(material), new EditorialPolicy("pending", null, 0, 1, Set.of(), false));
        assertThat(held.publicationId()).isNull();
        var eligible = process(List.of(material), policy());
        assertThat(eligible.eventVersion()).isEqualTo(2);
        assertThat(eligible.state()).isEqualTo("ELIGIBLE");
        assertThat(eligible.publicationId()).isNotBlank();
    }

    @Test void exhaustedRequestBudgetBlocksEditingAndQueueCreation() throws Exception {
        String period = UUID.randomUUID().toString();
        budgets.configureSyntheticPeriod(period, 100, 1);
        var first = pipeline.process(List.of(material()), policy(), new PipelineService.BudgetAllocation(period, 100));
        assertThat(first.state()).isEqualTo("ELIGIBLE");
        var second = pipeline.process(List.of(material()), policy(), new PipelineService.BudgetAllocation(period, 100));
        assertThat(second.reasons()).contains("BUDGET_EXHAUSTED");
        assertThat(second.publicationId()).isNull();
    }

    @Test void missingBudgetDoesNotPermitAnEditorRequest() throws Exception {
        var result = pipeline.process(List.of(material()), policy(), null);
        assertThat(result.reasons()).contains("BUDGET_NOT_CONFIGURED");
        assertThat(result.publicationId()).isNull();
    }

    private PipelineService.PipelineRun process(List<SourceMaterial> materials, EditorialPolicy policy) {
        return pipeline.process(materials, policy, new PipelineService.BudgetAllocation("integration-period", 100));
    }
    private SourceMaterial material() throws Exception {
        var original = usgs.parse(fixture("usgs.json"), NOW, true).getFirst();
        return copy(original, "test:" + UUID.randomUUID(), original.occurredAt(), NOW);
    }
    private static SourceMaterial copy(SourceMaterial m, String key, Instant occurred, Instant discovered) {
        return new SourceMaterial(m.sourceId(), m.externalId(), key, m.canonicalUrl(), m.headline(), m.topic(),
                m.producer(), m.evidenceChainId(), occurred, m.publishedAt(), m.sourceUpdatedAt(), discovered,
                m.facts(), m.discoveryOnly(), m.synthetic());
    }
    private static EditorialPolicy policy() { return new EditorialPolicy("integration-v1", Duration.ofHours(48), 2000,
            1, new LinkedHashSet<>(List.of("EARTHQUAKE", "OFFICIAL_ANNOUNCEMENT")), true); }
    private static String fixture(String name) throws Exception {
        try (var stream = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> current = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
}
