package com.globalnewsflash.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-27T10:30:00Z");
    private static final PublicationPolicy POLICY = new PublicationPolicy(Duration.ofMinutes(1), 3, 6, 24, Duration.ofMinutes(5));

    @Test
    void cadenceHonorsTheLatestOfDailyHourlyAndChannelCooldown() {
        var limit = new ChannelLimit("channel", hour(), 6, day(), 24, NOW.plusSeconds(10));
        var decision = limit.reserve(NOW, POLICY);
        assertThat(decision.reserved()).isNull();
        assertThat(decision.retryAt()).isEqualTo(Instant.parse("2026-10-28T00:00:00Z"));
    }

    @Test
    void aNewWindowResetsCountsButPreservesTheMinimumInterval() {
        var old = new ChannelLimit("channel", hour() - 3600, 6, day() - 86400, 24, NOW);
        var decision = old.reserve(NOW, POLICY);
        assertThat(decision.retryAt()).isNull();
        assertThat(decision.reserved().hourCount()).isEqualTo(1);
        assertThat(decision.reserved().dayCount()).isEqualTo(1);
        assertThat(decision.reserved().nextAllowedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void missingGatewayMessageIdRemainsUnknown() {
        var invalid = new DeliveryResult(DeliveryResult.Outcome.SUCCESS, null, null, null);
        assertThat(PublicationPolicy.validateResult(invalid).outcome()).isEqualTo(DeliveryResult.Outcome.UNKNOWN);
        var decision = POLICY.finish(operation(1), DeliveryResult.unknown("TIMEOUT"), null, NOW);
        assertThat(decision.state()).isEqualTo("UNKNOWN");
        assertThat(decision.retryAt()).isNull();
    }

    @Test
    void exhaustedRateLimitStillCoolsDownTheChannel() {
        var result = new DeliveryResult(DeliveryResult.Outcome.RATE_LIMITED, null, Duration.ofSeconds(30), "RATE_LIMITED");
        var decision = POLICY.finish(operation(3), result, null, NOW);
        assertThat(decision.state()).isEqualTo("FAILED");
        assertThat(decision.retryAt()).isNull();
        assertThat(decision.cooldownUntil()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void syntheticAndRealModesRemainSeparate() {
        assertThatThrownBy(() -> PublicationPolicy.requireMode(false, "OFFLINE", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublicationPolicy.requireMode(true, "LIVE", true)).isInstanceOf(IllegalArgumentException.class);
        PublicationPolicy.requireMode(false, "LIVE", true);
    }

    @Test
    void guardReasonsContainOnlyStableCodes() {
        assertThat(PublicationPolicy.guardReasons(List.of(List.of("SOURCE_REVOKED:private-article", "raw response text"))))
                .containsExactly("SOURCE_REVOKED", "GUARD_INVALID_REASON");
    }

    @Test
    void amendmentsSelectOnlyTheLatestConfirmedRealMessageForTheSameEventAndChannel() {
        var confirmed = history(2, "event", "channel", "SENT", false);
        assertThat(PublicationHistory.latestConfirmed(List.of(
                history(1, "event", "channel", "SENT", false), confirmed,
                history(3, "event", "channel", "UNKNOWN", false),
                history(4, "other-event", "channel", "SENT", false),
                history(5, "event", "other-channel", "SENT", false),
                history(6, "event", "channel", "SENT", true)), "event", "channel"))
                .isEqualTo(confirmed);
    }

    private static PublicationOperation history(int version, String event, String target, String state, boolean synthetic) {
        return new PublicationOperation("id-" + version, event, version, target, "SEND", state, "body", null,
                "message-" + version, 1, null, null, null, null, synthetic);
    }

    private static PublicationOperation operation(int attempts) {
        return new PublicationOperation("id", "event", 1, "channel", "SEND", "SENDING", "body", null,
                null, attempts, "lease", NOW.plusSeconds(60), null, null, false);
    }

    private static long hour() { return NOW.truncatedTo(ChronoUnit.HOURS).getEpochSecond(); }
    private static long day() { return NOW.truncatedTo(ChronoUnit.DAYS).getEpochSecond(); }
}
