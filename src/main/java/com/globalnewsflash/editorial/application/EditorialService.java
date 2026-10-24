package com.globalnewsflash.editorial.application;

import com.globalnewsflash.editorial.domain.EditorialDraft;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public final class EditorialService {
    private static final String PROMPT_VERSION = "synthetic-evidence-v1";
    private static final String CONTRACT_VERSION = "referenced-statements-v1";
    private static final Set<String> EXCLUDED_TOPICS = Set.of(
            "WAR_CASUALTIES", "UNCONFIRMED_ATTACK", "PERSONAL_ALLEGATION");
    private final Clock clock;
    private final EditorPort editor;

    public EditorialService(Clock clock, EditorPort editor) {
        this.clock = clock;
        this.editor = editor;
    }

    public EditorialResult evaluate(List<SourceMaterial> materials,
            List<SourcePolicy> sources, EditorialPolicy policy) {
        Instant now = clock.instant();
        String version = policy == null ? null : policy.version();
        if (policy == null || !policy.configured()) {
            return rejected(List.of("POLICY_NOT_READY"), null, version, now);
        }
        if (materials == null || materials.isEmpty()) {
            return rejected(List.of("NO_EVIDENCE"), null, version, now);
        }
        Set<String> reasons = new LinkedHashSet<>();
        Map<String, SourcePolicy> sourceMap = new LinkedHashMap<>();
        if (sources != null) {
            for (SourcePolicy source : sources) {
                if (source == null || sourceMap.putIfAbsent(source.sourceId(), source) != null) {
                    reasons.add("INVALID_SOURCE_REGISTRY");
                }
            }
        }
        Set<String> events = new LinkedHashSet<>();
        Set<String> chains = new LinkedHashSet<>();
        Map<String, String> factValues = new LinkedHashMap<>();
        Map<String, EditorRequest.EvidenceFact> evidence = new LinkedHashMap<>();
        for (SourceMaterial material : materials) {
            if (material == null) {
                reasons.add("INVALID_MATERIAL");
                continue;
            }
            events.add(material.eventKey());
            SourcePolicy source = sourceMap.get(material.sourceId());
            if (source == null) {
                reasons.add("SOURCE_NOT_REGISTERED:" + material.sourceId());
            } else {
                reasons.addAll(source.check(material));
            }
            if (!material.synthetic()) {
                reasons.add("REAL_EDITOR_NOT_CONFIGURED");
            }
            if (material.discoveryOnly()) {
                reasons.add("DISCOVERY_IS_NOT_EVIDENCE");
            }
            if (material.facts().isEmpty()) {
                reasons.add("INSUFFICIENT_MATERIAL");
            }
            if (EXCLUDED_TOPICS.contains(material.topic()) || !policy.allowedTopics().contains(material.topic())) {
                reasons.add("TOPIC_OUT_OF_SCOPE:" + material.topic());
            }
            Instant factualTime = material.occurredAt() == null ? material.publishedAt() : material.occurredAt();
            if (factualTime == null) {
                reasons.add("FACT_TIME_UNKNOWN");
            } else if (factualTime.isAfter(now)) {
                reasons.add("FACT_TIME_IN_FUTURE");
            } else if (factualTime.isBefore(now.minus(policy.maxAge()))) {
                reasons.add("STALE_EVENT");
            }
            if (material.evidenceChainId() == null || material.evidenceChainId().isBlank()) {
                reasons.add("EVIDENCE_CHAIN_UNKNOWN");
            } else if (!material.discoveryOnly() && !material.facts().isEmpty()) {
                chains.add(material.evidenceChainId());
            }
            for (SourceMaterial.Fact fact : material.facts()) {
                String previous = factValues.putIfAbsent(fact.id(), fact.text());
                if (previous != null && !previous.equals(fact.text())) {
                    reasons.add("FACT_CONFLICT:" + fact.id());
                }
                var value = new EditorRequest.EvidenceFact(material.sourceId(), material.externalId(),
                        fact.id(), fact.text(), material.canonicalUrl());
                String key = evidenceKey(value.sourceId(), value.materialId(), value.factId());
                var prior = evidence.putIfAbsent(key, value);
                if (prior != null && !prior.equals(value)) {
                    reasons.add("MATERIAL_VERSION_CONFLICT");
                }
            }
        }
        String eventKey = events.size() == 1 ? events.iterator().next() : null;
        if (events.size() != 1) {
            reasons.add("MULTIPLE_EVENTS");
        }
        if (chains.size() < policy.minimumIndependentChains()) {
            reasons.add("INSUFFICIENT_EVIDENCE_CHAINS");
        }
        if (!reasons.isEmpty()) {
            return rejected(List.copyOf(reasons), eventKey, version, now);
        }

        var request = new EditorRequest(UUID.randomUUID().toString(), eventKey, PROMPT_VERSION,
                CONTRACT_VERSION, evidence.values().stream()
                        .sorted(Comparator.comparing(EditorRequest.EvidenceFact::sourceId)
                                .thenComparing(EditorRequest.EvidenceFact::materialId)
                                .thenComparing(EditorRequest.EvidenceFact::factId))
                        .toList(), now, true);
        EditorResponse response;
        try {
            response = editor.edit(request);
        } catch (RuntimeException failure) {
            return rejected(List.of("EDITOR_FAILED"), eventKey, version, now);
        }
        List<String> violations = validate(response, request, evidence);
        if (!violations.isEmpty()) {
            return rejected(violations, eventKey, version, now);
        }
        String body = render(response.statements(), evidence, materials);
        if (body.codePointCount(0, body.length()) > policy.maxCharacters()) {
            return rejected(List.of("DRAFT_TOO_LONG"), eventKey, version, now);
        }
        var draft = new EditorialDraft(request.requestId(), response.statements(), body, response.provider(), response.model(),
                response.promptVersion(), response.contractVersion(), response.usage(), response.synthetic());
        return new EditorialResult(true, List.of(), draft, eventKey, version, now);
    }

    private static List<String> validate(EditorResponse response, EditorRequest request,
            Map<String, EditorRequest.EvidenceFact> evidence) {
        if (response == null) {
            return List.of("INVALID_EDITOR_RESPONSE");
        }
        Set<String> reasons = new LinkedHashSet<>();
        if (!response.synthetic() || isBlank(response.provider()) || isBlank(response.model())
                || !request.promptVersion().equals(response.promptVersion())
                || !request.contractVersion().equals(response.contractVersion())
                || response.usage() == null || response.statements().isEmpty()) {
            reasons.add("INVALID_EDITOR_RESPONSE");
        }
        Set<String> statements = new LinkedHashSet<>();
        for (EditorResponse.Statement statement : response.statements()) {
            if (statement == null) {
                reasons.add("INVALID_EDITOR_RESPONSE");
                continue;
            }
            String key = evidenceKey(statement.sourceId(), statement.materialId(), statement.factId());
            var fact = evidence.get(key);
            if (fact == null || !fact.text().equals(statement.text())) {
                reasons.add("UNSUPPORTED_STATEMENT");
            }
            if (!statements.add(key)) {
                reasons.add("DUPLICATE_STATEMENT");
            }
        }
        return List.copyOf(reasons);
    }

    private static String render(List<EditorResponse.Statement> statements,
            Map<String, EditorRequest.EvidenceFact> evidence, List<SourceMaterial> materials) {
        String facts = statements.stream().map(EditorResponse.Statement::text).distinct()
                .collect(Collectors.joining("\n"));
        String urls = statements.stream()
                .map(statement -> evidence.get(evidenceKey(statement.sourceId(), statement.materialId(), statement.factId())).sourceUrl())
                .distinct().collect(Collectors.joining("\n"));
        Instant occurredAt = materials.stream().map(SourceMaterial::occurredAt)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant sourcePublishedAt = materials.stream().map(SourceMaterial::publishedAt)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        String timeLabel = occurredAt == null ? "Source published (UTC): " : "Event time (UTC): ";
        Instant eventTime = occurredAt == null ? sourcePublishedAt : occurredAt;
        return "[SYNTHETIC / OFFLINE]\n" + facts + "\n\nSources:\n" + urls
                + "\n" + timeLabel + DateTimeFormatter.ISO_INSTANT.format(eventTime);
    }

    private static String evidenceKey(String sourceId, String materialId, String factId) {
        // Length prefixes avoid ambiguous keys even if external identifiers contain punctuation.
        return part(sourceId) + part(materialId) + part(factId);
    }

    private static String part(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static EditorialResult rejected(List<String> reasons, String eventKey, String version, Instant now) {
        return new EditorialResult(false, reasons, null, eventKey, version, now);
    }
}
