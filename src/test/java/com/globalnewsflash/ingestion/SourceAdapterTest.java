package com.globalnewsflash.ingestion;

import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.infrastructure.FederalReserveRssAdapter;
import com.globalnewsflash.ingestion.infrastructure.GdeltAdapter;
import com.globalnewsflash.ingestion.infrastructure.UsgsAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceAdapterTest {
    private final Instant discovered = Instant.parse("2026-10-24T01:10:00Z");
    private final UsgsAdapter usgs = new UsgsAdapter(JsonMapper.builder().build());
    private final GdeltAdapter gdelt = new GdeltAdapter(JsonMapper.builder().build());
    private final FederalReserveRssAdapter fed = new FederalReserveRssAdapter();

    @Test
    void usgsKeepsOccurrenceRevisionAndDiscoverySeparateAndSupportsRevisions() throws IOException {
        SourceMaterial original = usgs.parse(fixture("usgs.json"), discovered, true).getFirst();
        SourceMaterial revision = usgs.parse(fixture("usgs-revised.json"), discovered, true).getFirst();
        assertThat(original.eventKey()).isEqualTo("usgs:synthetic-earthquake-001");
        assertThat(original.synthetic()).isTrue();
        assertThat(original.producer()).isEqualTo("us");
        assertThat(original.occurredAt()).isEqualTo(Instant.parse("2026-10-24T01:00:00Z"));
        assertThat(original.sourceUpdatedAt()).isEqualTo(Instant.parse("2026-10-24T01:05:00Z"));
        assertThat(original.discoveredAt()).isEqualTo(discovered);
        assertThat(original.publishedAt()).isNull();
        assertThat(original.facts()).extracting(SourceMaterial.Fact::id)
                .containsExactly("magnitude", "place", "occurredAt");
        assertThat(original.facts().getFirst().text()).contains("USGS reports", "5.2");
        assertThat(revision.eventKey()).isEqualTo(original.eventKey());
        assertThat(revision.sourceUpdatedAt()).isAfter(original.sourceUpdatedAt());
        assertThat(revision.facts().getFirst().id()).isEqualTo(original.facts().getFirst().id());
        assertThat(revision.facts().getFirst().text()).contains("5.3");
    }

    @Test
    void missingMagnitudeDoesNotInventEvidenceAndTsunamiFlagDoesNotBecomeClaim() throws IOException {
        String payload = fixture("usgs.json").replace("\"mag\": 5.2", "\"mag\": null")
                .replace("\"tsunami\": 0", "\"tsunami\": 1");
        SourceMaterial material = usgs.parse(payload, discovered, true).getFirst();
        assertThat(material.facts()).isEmpty();
        SourceMaterial full = usgs.parse(fixture("usgs.json").replace("\"tsunami\": 0", "\"tsunami\": 1"), discovered, true).getFirst();
        assertThat(full.facts()).noneMatch(fact -> fact.text().toLowerCase().contains("tsunami"));
    }

    @Test
    void malformedOrIncompleteUsgsIsRejectedRatherThanPartiallyInvented() throws IOException {
        assertThatThrownBy(() -> usgs.parse("{}", discovered, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usgs.parse("not json", discovered, true)).isInstanceOf(IllegalArgumentException.class);
        String payload = fixture("usgs.json");
        assertThatThrownBy(() -> usgs.parse(payload.replace("\"id\": \"synthetic-earthquake-001\",", ""), discovered, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> usgs.parse(payload.replace("\"time\": 1792803600000", "\"time\": \"yesterday\""), discovered, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usgs.parse(payload + " {}", discovered, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usgs.parse(payload.replace("\"mag\": 5.2", "\"mag\":5.2,\"mag\":9"), discovered, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gdeltDiscoveryCannotBecomeEvidenceOrPublicationTime() throws IOException {
        SourceMaterial material = gdelt.parse(fixture("gdelt.json"), discovered, true).getFirst();
        assertThat(material.discoveryOnly()).isTrue();
        assertThat(material.facts()).isEmpty();
        assertThat(material.publishedAt()).isNull();
        assertThat(material.occurredAt()).isNull();
        assertThat(material.sourceUpdatedAt()).isNull();
        assertThat(material.discoveredAt()).isEqualTo(discovered);
        assertThat(material.canonicalUrl()).isEqualTo("https://synthetic.example.org/news/offline-sample");
        assertThatThrownBy(() -> gdelt.parse("{\"articles\":[{\"title\":\"title only\"}]}", discovered, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rssTitleAndDescriptionCannotBecomeDetailedPolicyFacts() throws IOException {
        SourceMaterial material = fed.parse(fixture("fed.xml"), discovered, true).getFirst();
        assertThat(material.sourceId()).isEqualTo("fed");
        assertThat(material.producer()).isEqualTo("federal-reserve-board");
        assertThat(material.publishedAt()).isEqualTo(Instant.parse("2026-10-24T01:00:00Z"));
        assertThat(material.occurredAt()).isNull();
        assertThat(material.sourceUpdatedAt()).isNull();
        assertThat(material.facts()).isEmpty();
        assertThat(material.discoveryOnly()).isFalse();
    }

    @Test
    void rssRejectsDtdAndEntitiesWithoutReadingExternalResources() {
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY stolen SYSTEM "file:///C:/Windows/win.ini">]>
                <rss><channel><item><title>&stolen;</title>
                <link>https://www.federalreserve.gov/test.htm</link></item></channel></rss>
                """;
        assertThatThrownBy(() -> fed.parse(malicious, discovered, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsafe");
        assertThatThrownBy(() -> fed.parse("<rss><channel>", discovered, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fed.parse("<not-rss/>", discovered, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsPreventUnboundedSourceProcessing() {
        assertThatThrownBy(() -> usgs.parse(" ".repeat(1_000_001), discovered, true))
                .isInstanceOf(IllegalArgumentException.class);
        String item = "{\"url\":\"https://synthetic.example.org/a\",\"title\":\"synthetic\"}";
        String payload = "{\"articles\":[" + String.join(",", java.util.Collections.nCopies(251, item)) + "]}";
        assertThatThrownBy(() -> gdelt.parse(payload, discovered, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    }

    @Test
    void syntheticFixturesCannotBeRelabeledAsRealMaterial() throws IOException {
        assertThatThrownBy(() -> usgs.parse(fixture("usgs.json"), discovered, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("synthetic");
        assertThatThrownBy(() -> gdelt.parse(fixture("gdelt.json"), discovered, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("synthetic");
        assertThatThrownBy(() -> fed.parse(fixture("fed.xml"), discovered, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("synthetic");
    }

    @Test
    void officialAdaptersRejectSpoofedLinksAndDuplicateRssFields() throws IOException {
        assertThatThrownBy(() -> usgs.parse(fixture("usgs.json").replace("earthquake.usgs.gov", "attacker.example.org"), discovered, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("official");
        assertThatThrownBy(() -> fed.parse(fixture("fed.xml").replace("www.federalreserve.gov", "attacker.example.org"), discovered, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("official");
        assertThatThrownBy(() -> fed.parse(fixture("fed.xml").replace("</guid>", "</guid><guid>another-id</guid>"), discovered, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void longGdeltUrlsRetainExternalIdentityWithStableBoundedEventKeys() {
        String url = "https://synthetic.example.org/" + "x".repeat(1500);
        String payload = "{\"articles\":[{\"url\":\"" + url + "\",\"title\":\"synthetic long URL\"}]}";
        SourceMaterial first = gdelt.parse(payload, discovered, true).getFirst();
        SourceMaterial rediscovered = gdelt.parse(payload, discovered.plusSeconds(20), true).getFirst();
        String trackedPayload = payload.replace(url, url + "?utm_source=another-discovery");
        SourceMaterial tracked = gdelt.parse(trackedPayload, discovered, true).getFirst();
        assertThat(first.canonicalUrl()).isEqualTo(url);
        assertThat(first.externalId()).isEqualTo(url);
        assertThat(first.eventKey()).matches("gdelt:[0-9a-f]{64}").hasSizeLessThan(240);
        assertThat(rediscovered.eventKey()).isEqualTo(first.eventKey());
        assertThat(tracked.eventKey()).isEqualTo(first.eventKey());
        String changedPayload = payload.replace(url, url + "y");
        assertThat(gdelt.parse(changedPayload, discovered, true).getFirst().eventKey()).isNotEqualTo(first.eventKey());
    }

    @Test
    void longFedGuidsAndUrlsRetainIdentityWithStableBoundedEventKeys() throws IOException {
        String guid = "synthetic-guid-" + "x".repeat(1500);
        String url = "https://www.federalreserve.gov/newsevents/" + "a".repeat(1500) + ".htm";
        String payload = fixture("fed.xml").replace("synthetic-fed-announcement-001", guid)
                .replace("https://www.federalreserve.gov/newsevents/pressreleases/synthetic-offline.htm", url);
        SourceMaterial first = fed.parse(payload, discovered, true).getFirst();
        SourceMaterial second = fed.parse(payload, discovered.plusSeconds(20), true).getFirst();
        assertThat(first.externalId()).isEqualTo(guid);
        assertThat(first.canonicalUrl()).isEqualTo(url);
        assertThat(first.eventKey()).matches("fed:[0-9a-f]{64}").hasSizeLessThan(240);
        assertThat(second.eventKey()).isEqualTo(first.eventKey());
        assertThat(first.evidenceChainId()).isEqualTo(first.eventKey());
        assertThat(fed.parse(payload.replace(guid, guid + "y"), discovered, true).getFirst().eventKey())
                .isNotEqualTo(first.eventKey());
    }

    @Test
    void overlongUsgsIdsFailBeforeExceedingPersistenceContract() throws IOException {
        String payload = fixture("usgs.json").replace("\"id\": \"synthetic-earthquake-001\"", "\"id\": \"" + "x".repeat(236) + "\"");
        assertThatThrownBy(() -> usgs.parse(payload, discovered, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity length");
    }

    private static String fixture(String name) throws IOException {
        try (var stream = SourceAdapterTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) throw new IOException("Missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
