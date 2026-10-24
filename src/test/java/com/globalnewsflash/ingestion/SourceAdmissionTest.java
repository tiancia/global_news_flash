package com.globalnewsflash.ingestion;

import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceAdmissionTest {
    @Test
    void admissionRequiresExplicitApprovalLicenseProducerAndMatchingMode() {
        SourceMaterial material = material(true, new ArrayList<>());
        assertThat(new SourcePolicy("usgs", true, true, true, "synthetic-v1", Set.of("us")).check(material))
                .isEmpty();
        assertThat(new SourcePolicy("usgs", false, false, false, null, Set.of()).check(material))
                .contains("SOURCE_DISABLED", "SOURCE_NOT_APPROVED", "LICENSE_NOT_RECORDED",
                        "MATERIAL_MODE_MISMATCH", "PRODUCER_NOT_PERMITTED");
        assertThat(new SourcePolicy("fed", true, true, true, "synthetic-v1", Set.of("us")).check(material))
                .contains("SOURCE_MISMATCH");
        SourceMaterial real = material(false, List.of());
        assertThat(new SourcePolicy("usgs", true, true, true, "synthetic-v1", Set.of("us")).check(real))
                .contains("MATERIAL_MODE_MISMATCH");
    }

    @Test
    void materialDoesNotExposeMutableFactsOrAllowDiscoveryEvidence() {
        var facts = new ArrayList<SourceMaterial.Fact>();
        SourceMaterial material = material(true, facts);
        facts.add(new SourceMaterial.Fact("new", "mutated after construction"));
        assertThat(material.facts()).isEmpty();
        assertThatThrownBy(() -> material.facts().add(new SourceMaterial.Fact("new", "mutation")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SourceMaterial("gdelt", "id", "event", "https://example.org/", "title",
                "DISCOVERY", "example.org", "chain", null, null, null, Instant.EPOCH,
                List.of(new SourceMaterial.Fact("fact", "not allowed")), true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void urlNormalizationDropsOnlyKnownTrackingAndFragment() {
        assertThat(CanonicalUrl.normalize("HTTPS://Example.ORG:443/a/../article?story=42&utm_source=test&lang=en#part"))
                .isEqualTo("https://example.org/article?story=42&lang=en");
        assertThat(CanonicalUrl.normalize("https://example.org/a%20b?q=a%2Fb&gclid=tracking"))
                .isEqualTo("https://example.org/a%20b?q=a%2Fb");
        assertThat(CanonicalUrl.normalize("https://example.org")).isEqualTo("https://example.org/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.org/a", "file:///etc/passwd", "https://user:pass@example.org/",
            "https://localhost/", "https://a.localhost/", "https://service.local/", "https://127.0.0.1/",
            "https://10.1.2.3/", "https://172.16.2.3/", "https://192.168.1.1/", "https://169.254.169.254/",
            "https://100.64.0.1/", "https://0.0.0.0/", "https://[::1]/", "https://[fc00::1]/",
            "https://[fe80::1]/", "https://example.org:8080/", "https://127.1/", "https://0177.0.0.1/"})
    void localOrUnsafeUrlsAreRejected(String url) {
        assertThatThrownBy(() -> CanonicalUrl.normalize(url)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SourceMaterial material(boolean synthetic, List<SourceMaterial.Fact> facts) {
        return new SourceMaterial("usgs", "id", "usgs:id", "https://earthquake.usgs.gov/id", "synthetic title",
                "EARTHQUAKE", "us", "usgs:id", Instant.EPOCH, null, Instant.EPOCH, Instant.EPOCH,
                facts, false, synthetic);
    }
}
