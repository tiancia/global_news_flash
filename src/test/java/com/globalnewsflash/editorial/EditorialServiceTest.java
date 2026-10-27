package com.globalnewsflash.editorial;

import com.globalnewsflash.editorial.application.EditorialService;
import com.globalnewsflash.editorial.application.EditorPort;
import com.globalnewsflash.editorial.application.EditorFailure;
import com.globalnewsflash.editorial.domain.EditorialDraft;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorResponse;
import com.globalnewsflash.editorial.infrastructure.SyntheticEditor;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorialServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String FACT = "USGS reports a magnitude 5.8 earthquake near Synthetic Island.";
    private final EditorialService service = new EditorialService(CLOCK, new SyntheticEditor());

    @Test
    void acceptsSingleAdmittedOfficialSourceWithoutInventingBackground() {
        var result = service.evaluate(List.of(material()), List.of(source()), policy(1));
        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.eventKey()).isEqualTo("usgs:synthetic-1");
        assertThat(result.draft().body()).isEqualTo("[SYNTHETIC / OFFLINE]\n" + FACT
                + "\n\nSources:\nhttps://earthquake.usgs.gov/earthquakes/eventpage/synthetic-1"
                + "\nEvent time (UTC): 2026-10-24T09:55:00Z");
        assertThat(result.draft().provider()).isEqualTo("synthetic");
        assertThat(result.draft().synthetic()).isTrue();
        assertThat(result.draft().statements()).hasSize(1);
    }

    @Test
    void repeatedEvaluationHasStableBodyButIndependentAuditMetadata() {
        var first = service.evaluate(List.of(material()), List.of(source()), policy(1));
        var laterService = new EditorialService(Clock.offset(CLOCK, Duration.ofMinutes(5)), new SyntheticEditor());
        var later = laterService.evaluate(List.of(material()), List.of(source()), policy(1));
        assertThat(later.draft().body()).isEqualTo(first.draft().body());
        assertThat(later.draft().requestId()).isNotEqualTo(first.draft().requestId());
        assertThat(later.evaluatedAt()).isAfter(first.evaluatedAt());
    }

    @Test
    void unconfiguredPolicyStopsBeforeEditorIsCalled() {
        AtomicInteger calls = new AtomicInteger();
        var neverCalled = new EditorialService(CLOCK, request -> {
            calls.incrementAndGet();
            throw new AssertionError("Editor must not execute");
        });
        var result = neverCalled.evaluate(List.of(material()), List.of(source()),
                new EditorialPolicy(null, null, 0, 0, null, false));
        assertThat(result.reasons()).containsExactly("POLICY_NOT_READY");
        assertThat(calls.get()).isZero();
        assertThat(result.draft()).isNull();
    }

    @Test
    void readyFlagCannotHideMissingThresholds() {
        var result = service.evaluate(List.of(material()), List.of(source()),
                new EditorialPolicy("test-only", Duration.ZERO, 500, 1, Set.of("EARTHQUAKE"), true));
        assertThat(result.reasons()).containsExactly("POLICY_NOT_READY");
    }

    @Test
    void rejectsEmptyEvidence() {
        assertThat(service.evaluate(List.of(), List.of(source()), policy(1)).reasons())
                .containsExactly("NO_EVIDENCE");
    }

    @Test
    void rejectsUnregisteredAndUnapprovedSources() {
        assertThat(service.evaluate(List.of(material()), List.of(), policy(1)).reasons())
                .contains("SOURCE_NOT_REGISTERED:usgs");
        var unapproved = new SourcePolicy("usgs", true, false, true, "synthetic-license", Set.of("us"));
        var result = service.evaluate(List.of(material()), List.of(unapproved), policy(1));
        assertThat(result.reasons()).contains("SOURCE_NOT_APPROVED");
        assertThat(result.draft()).isNull();
    }

    @Test
    void rejectsMaterialOutsideProducerAndLicenseScope() {
        var source = new SourcePolicy("usgs", true, true, true, null, Set.of("different-network"));
        assertThat(service.evaluate(List.of(material()), List.of(source), policy(1)).reasons())
                .contains("LICENSE_NOT_RECORDED", "PRODUCER_NOT_PERMITTED");
    }

    @Test
    void discoveryCannotCountAsEvidence() {
        var discovery = copy(material(), "DISCOVERY", "usgs:synthetic-1", "chain-1", null, null,
                List.of(), true, true);
        var result = service.evaluate(List.of(discovery), List.of(source()), policy(1));
        assertThat(result.reasons()).contains("DISCOVERY_IS_NOT_EVIDENCE", "INSUFFICIENT_MATERIAL",
                "FACT_TIME_UNKNOWN", "INSUFFICIENT_EVIDENCE_CHAINS");
    }

    @Test
    void headlineWithoutFactsCannotReachEditor() {
        var material = copy(material(), "EARTHQUAKE", "usgs:synthetic-1", "chain-1", NOW.minusSeconds(60),
                null, List.of(), false, true);
        assertThat(service.evaluate(List.of(material), List.of(source()), policy(1)).reasons())
                .contains("INSUFFICIENT_MATERIAL");
    }

    @Test
    void recentRediscoveryDoesNotMakeOldEventFresh() {
        var old = copy(material(), "EARTHQUAKE", "usgs:synthetic-1", "chain-1", NOW.minus(Duration.ofDays(5)),
                NOW.minusSeconds(10), material().facts(), false, true);
        assertThat(service.evaluate(List.of(old), List.of(source()), policy(1)).reasons()).contains("STALE_EVENT");
    }

    @Test
    void unknownAndFutureFactTimesAreRejected() {
        var unknown = copy(material(), "EARTHQUAKE", "usgs:synthetic-1", "chain-1", null, null,
                material().facts(), false, true);
        var future = copy(material(), "EARTHQUAKE", "usgs:synthetic-1", "chain-1", NOW.plusSeconds(1), null,
                material().facts(), false, true);
        assertThat(service.evaluate(List.of(unknown), List.of(source()), policy(1)).reasons()).contains("FACT_TIME_UNKNOWN");
        assertThat(service.evaluate(List.of(future), List.of(source()), policy(1)).reasons()).contains("FACT_TIME_IN_FUTURE");
    }

    @Test
    void publishingTimeCanBeUsedOnlyWhenOccurrenceIsUnknown() {
        var announcement = copy(material(), "OFFICIAL_ANNOUNCEMENT", "usgs:synthetic-1", "chain-1", null,
                NOW.minusSeconds(120), material().facts(), false, true);
        assertThat(service.evaluate(List.of(announcement), List.of(source()), policy(1)).eligible()).isTrue();
    }

    @Test
    void duplicatedAndSyndicatedEvidenceDoesNotBecomeIndependent() {
        var result = service.evaluate(List.of(material(), material()), List.of(source()), policy(2));
        assertThat(result.reasons()).contains("INSUFFICIENT_EVIDENCE_CHAINS");
        var singleRequired = service.evaluate(List.of(material(), material()), List.of(source()), policy(1));
        assertThat(singleRequired.eligible()).isTrue();
        assertThat(singleRequired.draft().statements()).hasSize(1);
    }

    @Test
    void extensionCanRequireIndependentEvidenceChains() {
        SourceMaterial other = new SourceMaterial("usgs", "synthetic-2", material().eventKey(),
                "https://earthquake.usgs.gov/earthquakes/eventpage/synthetic-2", "Synthetic event", "EARTHQUAKE",
                "us", "independent-chain", material().occurredAt(), null, null, NOW,
                material().facts(), false, true);
        assertThat(service.evaluate(List.of(material(), other), List.of(source()), policy(2)).eligible()).isTrue();
    }

    @Test
    void conflictsCannotBeResolvedByTheEditor() {
        var conflicting = new SourceMaterial("usgs", "synthetic-2", material().eventKey(), material().canonicalUrl(),
                "Synthetic event", "EARTHQUAKE", "us", "chain-2", material().occurredAt(), null, null, NOW,
                List.of(new SourceMaterial.Fact("magnitude", "USGS reports a magnitude 7.1 earthquake.")), false, true);
        var result = service.evaluate(List.of(material(), conflicting), List.of(source()), policy(1));
        assertThat(result.reasons()).contains("FACT_CONFLICT:magnitude");
        assertThat(result.draft()).isNull();
    }

    @Test
    void cannotMergeDifferentEventKeys() {
        var unrelated = copy(material(), "EARTHQUAKE", "usgs:other-event", "chain-1", material().occurredAt(),
                null, material().facts(), false, true);
        assertThat(service.evaluate(List.of(material(), unrelated), List.of(source()), policy(1)).reasons())
                .contains("MULTIPLE_EVENTS");
    }

    @Test
    void excludedRiskCannotBeEnabledByAllowedTopicList() {
        var highRisk = copy(material(), "WAR_CASUALTIES", material().eventKey(), "chain-1", material().occurredAt(),
                null, material().facts(), false, true);
        var policy = new EditorialPolicy("test-only", Duration.ofHours(24), 4096, 1, Set.of("WAR_CASUALTIES"), true);
        assertThat(service.evaluate(List.of(highRisk), List.of(source()), policy).reasons())
                .contains("TOPIC_OUT_OF_SCOPE:WAR_CASUALTIES");
    }

    @Test
    void realInputDoesNotUseSyntheticEditor() {
        var real = copy(material(), "EARTHQUAKE", material().eventKey(), "chain-1", material().occurredAt(),
                null, material().facts(), false, false);
        assertThat(service.evaluate(List.of(real), List.of(source()), policy(1)).reasons())
                .contains("REAL_EDITOR_NOT_CONFIGURED", "MATERIAL_MODE_MISMATCH");
    }

    @Test
    void editorTimeoutHasNoFallbackDraft() {
        var failing = new EditorialService(CLOCK, request -> { throw new IllegalStateException("Simulated timeout"); });
        var result = failing.evaluate(List.of(material()), List.of(source()), policy(1));
        assertThat(result.reasons()).containsExactly("EDITOR_FAILED");
        assertThat(result.draft()).isNull();
    }

    @Test
    void rejectsInventedWordsEvenWhenNumbersMatch() {
        EditorPort fabricated = request -> altered(new SyntheticEditor().edit(request),
                new EditorResponse.Statement("usgs", "synthetic-1", "magnitude",
                        "A magnitude 5.8 earthquake caused severe damage."));
        var result = new EditorialService(CLOCK, fabricated).evaluate(List.of(material()), List.of(source()), policy(1));
        assertThat(result.reasons()).containsExactly("UNSUPPORTED_STATEMENT");
        assertThat(result.draft()).isNull();
    }

    @Test
    void rejectsChangedNumberAndWrongEvidenceReference() {
        for (var statement : List.of(
                new EditorResponse.Statement("usgs", "synthetic-1", "magnitude", FACT.replace("5.8", "8.5")),
                new EditorResponse.Statement("different-source", "synthetic-1", "magnitude", FACT))) {
            var editor = new EditorialService(CLOCK, request -> altered(new SyntheticEditor().edit(request), statement));
            assertThat(editor.evaluate(List.of(material()), List.of(source()), policy(1)).reasons())
                    .contains("UNSUPPORTED_STATEMENT");
        }
    }

    @Test
    void metadataAndContractMustRemainTraceable() {
        var editor = new EditorialService(CLOCK, request -> new EditorResponse(
                new SyntheticEditor().edit(request).statements(), "synthetic", "exact-evidence-v1",
                "wrong-prompt", request.contractVersion(), new EditorResponse.Usage(0, 0, true), true));
        assertThat(editor.evaluate(List.of(material()), List.of(source()), policy(1)).reasons())
                .contains("INVALID_EDITOR_RESPONSE");
    }

    @Test
    void enforcesMaximumLengthOnFullyRenderedMessage() {
        var shortPolicy = new EditorialPolicy("test-only", Duration.ofHours(24), 30, 1, Set.of("EARTHQUAKE"), true);
        assertThat(service.evaluate(List.of(material()), List.of(source()), shortPolicy).reasons())
                .containsExactly("DRAFT_TOO_LONG");
    }

    @Test
    void storedDraftRemainsValidAfterSwitchingProviders() {
        var original = material();
        var real = copy(original, original.topic(), original.eventKey(), original.evidenceChainId(),
                original.occurredAt(), original.publishedAt(), original.facts(), false, false);
        var realSource = new SourcePolicy("usgs", true, true, false, "license-v1", Set.of("us"));
        var openai = new EditorialService(CLOCK, fakeLiveEditor("openai"));
        var deepseek = new EditorialService(CLOCK, fakeLiveEditor("deepseek"));
        var oldOpenAi = openai.evaluate(List.of(real), List.of(realSource), policy(1));
        var oldDeepSeek = deepseek.evaluate(List.of(real), List.of(realSource), policy(1));
        assertThat(oldOpenAi.eligible()).isTrue();
        assertThat(oldDeepSeek.eligible()).isTrue();
        assertThat(oldOpenAi.draft().promptVersion()).isEqualTo("grounded-english-news-v1");
        assertThat(oldDeepSeek.draft().promptVersion()).isEqualTo("grounded-english-news-deepseek-v2");
        assertThat(deepseek.validateStoredDraft(List.of(real), List.of(realSource), policy(1), oldOpenAi.draft())).isEmpty();
        assertThat(openai.validateStoredDraft(List.of(real), List.of(realSource), policy(1), oldDeepSeek.draft())).isEmpty();
        var legacyDeepSeek = withPrompt(oldDeepSeek.draft(), "grounded-english-news-v1");
        assertThat(openai.validateStoredDraft(List.of(real), List.of(realSource), policy(1), legacyDeepSeek)).isEmpty();
        assertThat(deepseek.validateStoredDraft(List.of(real), List.of(realSource), policy(1), legacyDeepSeek)).isEmpty();
        var unknownPrompt = withPrompt(oldDeepSeek.draft(), "unknown-prompt");
        assertThat(openai.validateStoredDraft(List.of(real), List.of(realSource), policy(1), unknownPrompt))
                .containsExactly("INVALID_EDITOR_RESPONSE");
        var wrongProviderVersion = withPrompt(oldOpenAi.draft(), "grounded-english-news-deepseek-v2");
        assertThat(deepseek.validateStoredDraft(List.of(real), List.of(realSource), policy(1), wrongProviderVersion))
                .containsExactly("INVALID_EDITOR_RESPONSE");
    }

    @Test
    void realDraftRequiresNumbersInTheQuoteAndUniqueEvidenceIds() {
        var original = material();
        var real = copy(original, original.topic(), original.eventKey(), original.evidenceChainId(),
                original.occurredAt(), original.publishedAt(), original.facts(), false, false);
        var realSource = new SourcePolicy("usgs", true, true, false, "license-v1", Set.of("us"));
        var wrongNumber = new EditorResponse.Statement("usgs", "synthetic-1", "magnitude",
                "USGS reports a magnitude 8.5 earthquake.", FACT);
        var first = new EditorResponse.Statement("usgs", "synthetic-1", "magnitude", FACT, FACT);
        var second = new EditorResponse.Statement("usgs", "synthetic-1", "magnitude",
                "USGS reports an earthquake near Synthetic Island.", FACT);
        var numericEditor = new EditorialService(CLOCK, fakeLiveStatements(List.of(wrongNumber)));
        var duplicateEditor = new EditorialService(CLOCK, fakeLiveStatements(List.of(first, second)));
        assertThatThrownBy(() -> numericEditor.evaluate(List.of(real), List.of(realSource), policy(1)))
                .isInstanceOfSatisfying(EditorFailure.class, f -> assertThat(f.code()).isEqualTo("UNSUPPORTED_NUMBER"));
        assertThatThrownBy(() -> duplicateEditor.evaluate(List.of(real), List.of(realSource), policy(1)))
                .isInstanceOfSatisfying(EditorFailure.class, f -> assertThat(f.code()).isEqualTo("DUPLICATE_STATEMENT"));
        var valid = new EditorialService(CLOCK, fakeLiveStatements(List.of(first)));
        assertThat(valid.evaluate(List.of(real), List.of(realSource), policy(1)).eligible()).isTrue();
    }

    private static EditorPort fakeLiveStatements(List<EditorResponse.Statement> statements) {
        return new EditorPort() {
            @Override public boolean supports(boolean synthetic) { return !synthetic; }
            @Override public EditorResponse edit(com.globalnewsflash.editorial.domain.EditorRequest request) {
                return new EditorResponse(statements, "deepseek", "test-model", request.promptVersion(),
                        request.contractVersion(), new EditorResponse.Usage(100, 50, false), false, "LOW", true);
            }
        };
    }

    private static EditorialDraft withPrompt(EditorialDraft draft, String prompt) {
        return new EditorialDraft(draft.requestId(), draft.statements(), draft.body(), draft.provider(), draft.model(),
                prompt, draft.contractVersion(), draft.usage(), draft.synthetic());
    }

    private static EditorPort fakeLiveEditor(String provider) {
        return new EditorPort() {
            @Override public boolean supports(boolean synthetic) { return !synthetic; }
            @Override public String livePromptVersion() {
                return "deepseek".equals(provider) ? "grounded-english-news-deepseek-v2" : EditorPort.super.livePromptVersion();
            }
            @Override public EditorResponse edit(com.globalnewsflash.editorial.domain.EditorRequest request) {
                var fact = request.facts().getFirst();
                var statement = new EditorResponse.Statement(fact.sourceId(), fact.materialId(), fact.factId(),
                        fact.text(), fact.text());
                return new EditorResponse(List.of(statement), provider, "test-model", request.promptVersion(),
                        request.contractVersion(), new EditorResponse.Usage(100, 50, false), false, "LOW", true);
            }
        };
    }

    private static EditorResponse altered(EditorResponse response, EditorResponse.Statement statement) {
        return new EditorResponse(List.of(statement), response.provider(), response.model(), response.promptVersion(),
                response.contractVersion(), response.usage(), response.synthetic());
    }

    private static EditorialPolicy policy(int chains) {
        return new EditorialPolicy("synthetic-test-policy-v1", Duration.ofHours(24), 4096,
                chains, Set.of("EARTHQUAKE", "OFFICIAL_ANNOUNCEMENT"), true);
    }

    private static SourcePolicy source() {
        return new SourcePolicy("usgs", true, true, true, "synthetic-test-license-v1", Set.of("us"));
    }

    private static SourceMaterial material() {
        return new SourceMaterial("usgs", "synthetic-1", "usgs:synthetic-1",
                "https://earthquake.usgs.gov/earthquakes/eventpage/synthetic-1", "Synthetic earthquake", "EARTHQUAKE",
                "us", "chain-1", NOW.minusSeconds(300), null, NOW.minusSeconds(60), NOW,
                List.of(new SourceMaterial.Fact("magnitude", FACT)), false, true);
    }

    private static SourceMaterial copy(SourceMaterial original, String topic, String eventKey, String chain,
            Instant occurred, Instant published, List<SourceMaterial.Fact> facts, boolean discovery, boolean synthetic) {
        return new SourceMaterial(original.sourceId(), original.externalId(), eventKey, original.canonicalUrl(),
                original.headline(), topic, original.producer(), chain, occurred, published, original.sourceUpdatedAt(),
                original.discoveredAt(), facts, discovery, synthetic);
    }
}
