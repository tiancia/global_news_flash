package com.globalnewsflash.automation.domain;

import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Fictional operating inputs, never live editorial policy. */
public enum DemoScenario {
    VALID, REVISED, STALE, DISCOVERY, TITLE_ONLY;

    public String source() { return switch (this) { case DISCOVERY -> "gdelt"; case TITLE_ONLY -> "fed"; default -> "usgs"; }; }
    public String fixture() {
        return switch (this) { case REVISED -> "usgs-revised.json"; case DISCOVERY -> "gdelt.json"; case TITLE_ONLY -> "fed.xml"; default -> "usgs.json"; };
    }
    public EditorialPolicy policy() {
        return new EditorialPolicy("demo-" + (this == REVISED ? "valid" : name().toLowerCase(java.util.Locale.ROOT)) + "-v1",
                this == STALE ? Duration.ofSeconds(1) : Duration.ofHours(48), 2000, 1, Set.of("EARTHQUAKE", "OFFICIAL_ANNOUNCEMENT"), true);
    }
    public List<SourceMaterial> materials(List<SourceMaterial> input) {
        if (this != STALE) return input;
        return input.stream().map(m -> new SourceMaterial(m.sourceId(), m.externalId(), m.eventKey() + ":stale", m.canonicalUrl(),
                m.headline(), m.topic(), m.producer(), m.evidenceChainId(), m.occurredAt(), m.publishedAt(), m.sourceUpdatedAt(),
                m.discoveredAt(), m.facts(), m.discoveryOnly(), true)).toList();
    }
}
