package com.globalnewsflash.digest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DigestPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-27T22:00:00Z");

    @Test
    void candidatesKeepLatestVersionsAndSortDeterministically() {
        var old = new Candidate("old", "event-a", 1, NOW);
        var latest = new Candidate("new", "event-a", 2, NOW.minusSeconds(10));
        var other = new Candidate("other", "event-b", 1, NOW);
        assertThat(DigestPolicy.latestCandidates(List.of(old, latest, other))).containsExactly(other, latest);
    }

    @Test
    void oversizedBriefIsSkippedWithoutTruncatingOtherBriefs() {
        var large = new DigestItem("large", "a", 1, "x".repeat(500), NOW);
        var small = new DigestItem("small", "b", 1, "Complete verified brief", NOW);
        int limit = DigestRenderer.length(DigestRenderer.render("2026-10-27", List.of(small)));
        assertThat(DigestPolicy.select("2026-10-27", List.of(large, small), 5, limit)).containsExactly(small);
    }

    @Test
    void windowsCannotReachIntoTheFutureOrBeEmpty() {
        var item = new DigestItem("id", "event", 1, "brief", NOW);
        assertThat(DigestEvidencePolicy.validWindow(List.of(item), "channel", NOW.minusSeconds(60), NOW, NOW)).isTrue();
        assertThat(DigestEvidencePolicy.validWindow(List.of(item), "channel", NOW, NOW, NOW)).isFalse();
        assertThat(DigestEvidencePolicy.validWindow(List.of(item), "channel", NOW.minusSeconds(60), NOW.plusSeconds(1), NOW)).isFalse();
    }

    @Test
    void digestDueTimeUsesUtc() {
        assertThat(DigestPolicy.due(NOW.minusSeconds(1), 22)).isFalse();
        assertThat(DigestPolicy.due(NOW, 22)).isTrue();
    }
}
