package com.globalnewsflash.editorial.domain;

import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class EditorialRules {
    private static final String SYNTHETIC_PROMPT = "synthetic-evidence-v1";
    private static final String SYNTHETIC_CONTRACT = "referenced-statements-v1";
    private static final String LIVE_PROMPT = "grounded-english-news-v1";
    private static final String DEEPSEEK_PROMPT_V2 = "grounded-english-news-deepseek-v2";
    private static final String LIVE_CONTRACT = "quoted-statements-v1";
    private static final Set<String> EXCLUDED_TOPICS = Set.of(
            "WAR_CASUALTIES", "UNCONFIRMED_ATTACK", "PERSONAL_ALLEGATION");
    private static final Pattern REVIEW_LANGUAGE = Pattern.compile(
            "(?i)\\b(killed|casualties|fatalities|death toll|airstrikes?|invasion|unconfirmed|allegations?|accused|diagnos(?:is|ed)|dosage)\\b");
    private static final Pattern NUMBERS = Pattern.compile("\\d+(?:[.,]\\d+)*%?");
    private final Clock clock;
    private final boolean supportsSynthetic;
    private final boolean supportsReal;
    private final String livePromptVersion;

    public EditorialRules(Clock clock, boolean supportsSynthetic, boolean supportsReal, String livePromptVersion) {
        this.clock = clock;
        this.supportsSynthetic = supportsSynthetic;
        this.supportsReal = supportsReal;
        this.livePromptVersion = livePromptVersion;
    }

    public record Prepared(Analysis analysis, EditorRequest request, EditorialResult rejection, Instant evaluatedAt) {}

    /** Pure evidence gate: it never invokes a model or reserves budget. */
    public List<String> validateEvidence(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy) {
        return analyze(materials, sources, policy).reasons();
    }

    public Prepared prepare(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy) {
        Instant now = clock.instant();
        var analysis = analyze(materials, sources, policy);
        String version = policy == null ? null : policy.version();
        if (!analysis.reasons().isEmpty()) return new Prepared(analysis, null, rejected(analysis.reasons(), analysis.eventKey(), version, now), now);
        boolean synthetic = analysis.synthetic();
        var request = new EditorRequest(UUID.randomUUID().toString(), analysis.eventKey(),
                synthetic ? SYNTHETIC_PROMPT : livePromptVersion, synthetic ? SYNTHETIC_CONTRACT : LIVE_CONTRACT,
                analysis.evidence().values().stream().sorted(Comparator.comparing(EditorRequest.EvidenceFact::sourceId)
                        .thenComparing(EditorRequest.EvidenceFact::materialId).thenComparing(EditorRequest.EvidenceFact::factId))
                        .toList(), now, synthetic);
        return new Prepared(analysis, request, null, now);
    }

    public EditorialResult complete(Prepared prepared, List<SourceMaterial> materials,
            EditorialPolicy policy, EditorResponse response) {
        var analysis = prepared.analysis();
        var request = prepared.request();
        var now = prepared.evaluatedAt();
        var version = policy.version();
        boolean synthetic = analysis.synthetic();
        List<String> violations = validate(response, request, analysis.evidence());
        if (!violations.isEmpty()) {
            if (!synthetic) throw accountedFailure(violations.getFirst(), response);
            return rejected(violations, analysis.eventKey(), version, now);
        }
        String body = render(response.statements(), analysis.evidence(), materials, synthetic);
        if (body.codePointCount(0, body.length()) > policy.maxCharacters()) {
            if (!synthetic) throw accountedFailure("DRAFT_TOO_LONG", response);
            return rejected(List.of("DRAFT_TOO_LONG"), analysis.eventKey(), version, now);
        }
        var draft = new EditorialDraft(request.requestId(), response.statements(), body, response.provider(), response.model(),
                response.promptVersion(), response.contractVersion(), response.usage(), response.synthetic());
        return new EditorialResult(true, List.of(), draft, analysis.eventKey(), version, now);
    }

    public EditorialResult editorFailed(Prepared prepared, EditorialPolicy policy) {
        if (!prepared.analysis().synthetic()) throw new EditorFailure("EDITOR_FAILED", true);
        return rejected(List.of("EDITOR_FAILED"), prepared.analysis().eventKey(), policy.version(), prepared.evaluatedAt());
    }

    /** Revalidates the immutable saved draft and current evidence without making a second AI request. */
    public List<String> validateStoredDraft(List<SourceMaterial> materials, List<SourcePolicy> sources,
            EditorialPolicy policy, EditorialDraft draft) {
        var analysis = analyze(materials, sources, policy);
        if (!analysis.reasons().isEmpty()) return analysis.reasons();
        if (draft == null) return List.of("INVALID_EDITOR_RESPONSE");
        boolean synthetic = analysis.synthetic();
        // Historical drafts keep their original prompt version when the active provider changes.
        boolean knownPrompt = synthetic ? SYNTHETIC_PROMPT.equals(draft.promptVersion())
                : LIVE_PROMPT.equals(draft.promptVersion())
                    || ("deepseek".equals(draft.provider()) && DEEPSEEK_PROMPT_V2.equals(draft.promptVersion()));
        if (!knownPrompt) return List.of("INVALID_EDITOR_RESPONSE");
        var request = new EditorRequest(draft.requestId(), analysis.eventKey(),
                draft.promptVersion(), synthetic ? SYNTHETIC_CONTRACT : LIVE_CONTRACT,
                List.copyOf(analysis.evidence().values()), clock.instant(), synthetic);
        var response = new EditorResponse(draft.statements(), draft.provider(), draft.model(), draft.promptVersion(),
                draft.contractVersion(), draft.usage(), draft.synthetic(), "LOW", true);
        var reasons = new LinkedHashSet<>(validate(response, request, analysis.evidence()));
        if (reasons.isEmpty() && !render(draft.statements(), analysis.evidence(), materials, synthetic).equals(draft.body())) {
            reasons.add("STORED_DRAFT_BODY_MISMATCH");
        }
        if (draft.body() == null || draft.body().codePointCount(0, draft.body().length()) > policy.maxCharacters()) {
            reasons.add("DRAFT_TOO_LONG");
        }
        return List.copyOf(reasons);
    }

    private Analysis analyze(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy) {
        if (policy == null || !policy.configured()) return new Analysis(List.of("POLICY_NOT_READY"), null, Map.of(), true);
        if (materials == null || materials.isEmpty()) return new Analysis(List.of("NO_EVIDENCE"), null, Map.of(), true);
        Instant now = clock.instant();
        var reasons = new LinkedHashSet<String>();
        var sourceMap = new LinkedHashMap<String, SourcePolicy>();
        if (sources != null) {
            for (SourcePolicy source : sources) {
                if (source == null || sourceMap.putIfAbsent(source.sourceId(), source) != null) reasons.add("INVALID_SOURCE_REGISTRY");
            }
        }
        var events = new LinkedHashSet<String>();
        var chains = new LinkedHashSet<String>();
        var modes = new LinkedHashSet<Boolean>();
        var factValues = new LinkedHashMap<String, String>();
        var evidence = new LinkedHashMap<String, EditorRequest.EvidenceFact>();
        for (SourceMaterial material : materials) {
            if (material == null) { reasons.add("INVALID_MATERIAL"); continue; }
            events.add(material.eventKey()); modes.add(material.synthetic());
            SourcePolicy source = sourceMap.get(material.sourceId());
            if (source == null) reasons.add("SOURCE_NOT_REGISTERED:" + material.sourceId());
            else reasons.addAll(source.check(material));
            if (!(material.synthetic() ? supportsSynthetic : supportsReal)) {
                reasons.add(material.synthetic() ? "SYNTHETIC_EDITOR_NOT_CONFIGURED" : "REAL_EDITOR_NOT_CONFIGURED");
            }
            if (material.discoveryOnly()) reasons.add("DISCOVERY_IS_NOT_EVIDENCE");
            if (material.facts().isEmpty()) reasons.add("INSUFFICIENT_MATERIAL");
            if (EXCLUDED_TOPICS.contains(material.topic()) || !policy.allowedTopics().contains(material.topic())) {
                reasons.add("TOPIC_OUT_OF_SCOPE:" + material.topic());
            }
            if (!material.synthetic() && (REVIEW_LANGUAGE.matcher(material.headline()).find()
                    || material.facts().stream().anyMatch(f -> REVIEW_LANGUAGE.matcher(f.text()).find()))) {
                reasons.add("HIGH_RISK_MATERIAL_REQUIRES_REVIEW");
            }
            Instant factualTime = material.occurredAt() == null ? material.publishedAt() : material.occurredAt();
            if (factualTime == null) reasons.add("FACT_TIME_UNKNOWN");
            else if (factualTime.isAfter(now)) reasons.add("FACT_TIME_IN_FUTURE");
            else if (factualTime.isBefore(now.minus(policy.maxAge()))) reasons.add("STALE_EVENT");
            if (material.evidenceChainId() == null || material.evidenceChainId().isBlank()) reasons.add("EVIDENCE_CHAIN_UNKNOWN");
            else if (!material.discoveryOnly() && !material.facts().isEmpty()) chains.add(material.evidenceChainId());
            for (SourceMaterial.Fact fact : material.facts()) {
                String previous = factValues.putIfAbsent(fact.id(), fact.text());
                if (previous != null && !previous.equals(fact.text())) reasons.add("FACT_CONFLICT:" + fact.id());
                var value = new EditorRequest.EvidenceFact(material.sourceId(), material.externalId(), fact.id(), fact.text(), material.canonicalUrl());
                var prior = evidence.putIfAbsent(evidenceKey(value.sourceId(), value.materialId(), value.factId()), value);
                if (prior != null && !prior.equals(value)) reasons.add("MATERIAL_VERSION_CONFLICT");
            }
        }
        String eventKey = events.size() == 1 ? events.iterator().next() : null;
        if (events.size() != 1) reasons.add("MULTIPLE_EVENTS");
        if (modes.size() != 1) reasons.add("MIXED_MATERIAL_MODES");
        if (chains.size() < policy.minimumIndependentChains()) reasons.add("INSUFFICIENT_EVIDENCE_CHAINS");
        return new Analysis(List.copyOf(reasons), eventKey, Map.copyOf(evidence), !modes.contains(false));
    }

    private static List<String> validate(EditorResponse response, EditorRequest request,
            Map<String, EditorRequest.EvidenceFact> evidence) {
        if (response == null) return List.of("INVALID_EDITOR_RESPONSE");
        var reasons = new LinkedHashSet<String>();
        if (response.synthetic() != request.synthetic() || isBlank(response.provider()) || isBlank(response.model())
                || !request.promptVersion().equals(response.promptVersion()) || !request.contractVersion().equals(response.contractVersion())
                || response.usage() == null || response.statements().isEmpty()) reasons.add("INVALID_EDITOR_RESPONSE");
        if (!request.synthetic()) {
            if (!("openai".equals(response.provider()) || "deepseek".equals(response.provider())) || response.usage() == null || response.usage().estimated()) reasons.add("INVALID_EDITOR_RESPONSE");
            if (!"LOW".equals(response.risk())) reasons.add("EDITOR_RISK_REQUIRES_REVIEW");
            if (!response.relevant()) reasons.add("EDITOR_NOT_RELEVANT");
        }
        var statements = new LinkedHashSet<String>();
        for (EditorResponse.Statement statement : response.statements()) {
            if (statement == null) { reasons.add("INVALID_EDITOR_RESPONSE"); continue; }
            String key = evidenceKey(statement.sourceId(), statement.materialId(), statement.factId());
            var fact = evidence.get(key);
            if (fact == null) reasons.add("UNSUPPORTED_STATEMENT");
            else if (request.synthetic()) {
                if (!fact.text().equals(statement.text())) reasons.add("UNSUPPORTED_STATEMENT");
            } else {
                // Quote existence and number preservation are necessary grounding checks, not a
                // proof that a natural-language paraphrase semantically follows from its quote.
                String quote = statement.quote();
                if (quote == null || quote.isBlank() || quote.length() < Math.min(16, fact.text().length())
                        || quote.length() > 1_600 || !fact.text().contains(quote)) reasons.add("UNSUPPORTED_QUOTE");
                if (statement.text() == null || statement.text().isBlank() || statement.text().length() > 600
                        || !englishText(statement.text()) || statement.text().contains("http://") || statement.text().contains("https://")) {
                    reasons.add("INVALID_ENGLISH_STATEMENT");
                } else if (quote != null && !numbers(quote).containsAll(numbers(statement.text()))) {
                    reasons.add("UNSUPPORTED_NUMBER");
                }
                if (statement.text() != null && REVIEW_LANGUAGE.matcher(statement.text()).find()) reasons.add("EDITOR_RISK_REQUIRES_REVIEW");
            }
            if (!statements.add(key)) reasons.add("DUPLICATE_STATEMENT");
        }
        return List.copyOf(reasons);
    }

    private static boolean englishText(String text) {
        long latin = text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN).count();
        long letters = text.codePoints().filter(Character::isLetter).count();
        return latin >= 10 && latin * 10 >= letters * 9
                && text.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n');
    }

    private static Set<String> numbers(String text) {
        return NUMBERS.matcher(text).results().map(java.util.regex.MatchResult::group).collect(Collectors.toSet());
    }

    private static String render(List<EditorResponse.Statement> statements,
            Map<String, EditorRequest.EvidenceFact> evidence, List<SourceMaterial> materials, boolean synthetic) {
        String facts = statements.stream().map(statement -> synthetic ? statement.text()
                : "According to " + URI.create(evidence.get(evidenceKey(statement.sourceId(), statement.materialId(), statement.factId())).sourceUrl()).getHost()
                    + ", " + statement.text()).distinct().collect(Collectors.joining("\n"));
        String urls = statements.stream().map(statement -> evidence.get(evidenceKey(statement.sourceId(), statement.materialId(), statement.factId())).sourceUrl())
                .distinct().collect(Collectors.joining("\n"));
        Instant occurredAt = materials.stream().map(SourceMaterial::occurredAt).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant sourcePublishedAt = materials.stream().map(SourceMaterial::publishedAt).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        String timeLabel = occurredAt == null ? "Source published (UTC): " : "Event time (UTC): ";
        Instant eventTime = occurredAt == null ? sourcePublishedAt : occurredAt;
        return (synthetic ? "[SYNTHETIC / OFFLINE]\n" : "") + facts + "\n\nSources:\n" + urls
                + "\n" + timeLabel + DateTimeFormatter.ISO_INSTANT.format(eventTime);
    }

    private static EditorFailure accountedFailure(String code, EditorResponse response) {
        var usage = response == null ? null : response.usage();
        return new EditorFailure(code, usage == null || usage.estimated(), usage);
    }

    private static String evidenceKey(String sourceId, String materialId, String factId) { return part(sourceId) + part(materialId) + part(factId); }
    private static String part(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    private static EditorialResult rejected(List<String> reasons, String eventKey, String version, Instant now) {
        return new EditorialResult(false, reasons, null, eventKey, version, now);
    }
    public record Analysis(List<String> reasons, String eventKey, Map<String, EditorRequest.EvidenceFact> evidence, boolean synthetic) {}
}
