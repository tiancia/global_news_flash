package com.globalnewsflash.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IngestionPolicyTest {
    @Test
    void synchronizationPreservesAnOperatorsDisabledSource() {
        assertThat(IngestionPolicy.enabledAfterSync(true, true, false)).isFalse();
        assertThat(IngestionPolicy.enabledAfterSync(true, false, false)).isTrue();
        assertThat(IngestionPolicy.enabledAfterSync(false, false, null)).isFalse();
    }

    @Test
    void successfulAndUncertainWorkCannotBeRequeued() {
        for (String state : new String[] {"ELIGIBLE", "SENT", "UNKNOWN", "UNCERTAIN", "PROCESSING"}) {
            assertThatThrownBy(() -> IngestionPolicy.requireRequeueable(row(state))).isInstanceOf(IllegalStateException.class);
        }
        IngestionPolicy.requireRequeueable(row("HELD"));
    }

    @Test
    void retryBackoffRemainsBounded() {
        assertThat(IngestionPolicy.backoff(1)).isEqualTo(30_000);
        assertThat(IngestionPolicy.backoff(2)).isEqualTo(60_000);
        assertThat(IngestionPolicy.backoff(Integer.MAX_VALUE)).isEqualTo(3_600_000);
    }

    @Test
    void realSourceEnablementStillRequiresExplicitAdmission() {
        var source = new SourceRegistration("id", "fed", false, false, false, "pending", "", "scope");
        assertThatThrownBy(() -> source.requireEnablement(true, true)).isInstanceOf(IllegalArgumentException.class);
        source.requireEnablement(false, false);
    }

    private static InboxRow row(String state) {
        return new InboxRow("id", "source", "event", "fingerprint", "payload", state, "detail", 1, 0, null, 0, 0, null);
    }
}
