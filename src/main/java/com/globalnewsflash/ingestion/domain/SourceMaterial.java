package com.globalnewsflash.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A material version. Discovery metadata and evidence are deliberately distinct. */
public record SourceMaterial(
        String sourceId, String externalId, String eventKey, String canonicalUrl,
        String headline, String topic, String producer, String evidenceChainId,
        Instant occurredAt, Instant publishedAt, Instant sourceUpdatedAt, Instant discoveredAt,
        List<Fact> facts, boolean discoveryOnly, boolean synthetic) {
    public SourceMaterial {
        requireText(sourceId, "sourceId");
        requireText(externalId, "externalId");
        requireText(eventKey, "eventKey");
        canonicalUrl = CanonicalUrl.normalize(canonicalUrl);
        requireText(headline, "headline");
        requireText(topic, "topic");
        requireText(producer, "producer");
        requireText(evidenceChainId, "evidenceChainId");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        if (facts.stream().map(Fact::id).distinct().count() != facts.size()) {
            throw new IllegalArgumentException("Duplicate fact identifiers");
        }
        if (discoveryOnly && !facts.isEmpty()) {
            throw new IllegalArgumentException("Discovery metadata cannot contain evidence facts");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record Fact(String id, String text) {
        public Fact {
            requireText(id, "fact id");
            requireText(text, "fact text");
        }
    }
    /** Rediscovery is not a material revision. The caller chooses the historical snapshot format. */
    public List<Object> versionIdentity(boolean includeSynthetic, boolean sortFacts) {
        var identity = new java.util.ArrayList<Object>(java.util.Arrays.asList(sourceId, externalId, eventKey, canonicalUrl,
                headline, topic, producer, evidenceChainId, occurredAt, publishedAt, sourceUpdatedAt,
                sortFacts ? facts.stream().sorted(java.util.Comparator.comparing(Fact::id)).toList() : facts, discoveryOnly));
        if (includeSynthetic) identity.add(synthetic);
        return identity;
    }

}
