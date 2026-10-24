package com.globalnewsflash.growth;

import com.globalnewsflash.growth.application.GrowthService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:growth-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class GrowthServiceTest {
    @Autowired GrowthService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM growth_join_event");
        jdbc.update("DELETE FROM growth_distribution");
        jdbc.update("DELETE FROM growth_invite");
        jdbc.update("DELETE FROM growth_target");
    }

    @Test
    void targetsStartUnauthorizedAndOnlySyntheticMaterialsCanDistribute() {
        assertThat(service.report().distributions()).isZero();
        assertThatThrownBy(() -> service.distribute("op-1", "target-1", text("verified report")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_AUTHORIZED");
        service.authorizeSyntheticTarget("target-1", "Synthetic partner");
        assertThatThrownBy(() -> service.distribute("op-1", "target-1", "Unmarked real content"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NON_SYNTHETIC");
        var delivered = service.distribute("op-1", "target-1", text("verified report"));
        assertThat(delivered.status()).isEqualTo("SIMULATED");
        assertThat(delivered.synthetic()).isTrue();
        assertThat(service.report().distributions()).isEqualTo(1);
        service.revokeTarget("target-1");
        assertThatThrownBy(() -> service.distribute("op-2", "target-1", text("next report")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_AUTHORIZED");
        assertThatThrownBy(() -> service.registerSyntheticInvite("target-1", "invite-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_AUTHORIZED");
    }

    @Test
    void operationKeysAreIdempotentButCannotBeReusedForDifferentBodiesOrTargets() {
        service.authorizeSyntheticTarget("target-1", "One");
        service.authorizeSyntheticTarget("target-2", "Two");
        var first = service.distribute("same-key", "target-1", text("first"));
        assertThat(service.distribute("same-key", "target-1", text("first"))).isEqualTo(first);
        assertThatThrownBy(() -> service.distribute("same-key", "target-1", text("changed")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> service.distribute("same-key", "target-2", text("first")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThat(service.report().distributions()).isEqualTo(1);
    }

    @Test
    void joinAttributionPreservesUnknownAndDeduplicatesUpdateIds() {
        service.authorizeSyntheticTarget("target-1", "One");
        service.registerSyntheticInvite("target-1", "offline-invite-1");
        var known = service.recordJoin(1001, "offline-invite-1");
        assertThat(known.attribution()).isEqualTo("KNOWN");
        assertThat(known.targetId()).isEqualTo("target-1");
        assertThat(service.recordJoin(1001, "offline-invite-1")).isEqualTo(known);
        assertThatThrownBy(() -> service.recordJoin(1001, "different-invite"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        var unknown = service.recordJoin(1002, "unrecognized-key");
        assertThat(unknown.attribution()).isEqualTo("UNKNOWN");
        assertThat(unknown.targetId()).isNull();
        assertThat(unknown.inviteKey()).isEqualTo("unrecognized-key");
        assertThat(service.recordJoin(1003, null).reason()).isEqualTo("MISSING_INVITE");
        service.revokeTarget("target-1");
        assertThat(service.recordJoin(1004, "offline-invite-1").attribution()).isEqualTo("UNKNOWN");
        assertThat(service.report().knownJoins()).isEqualTo(1);
        assertThat(service.report().unknownJoins()).isEqualTo(3);
        assertThat(service.report().synthetic()).isTrue();
    }

    @Test
    void inviteKeysCannotBeReboundOrTreatedAsLiveLinks() {
        service.authorizeSyntheticTarget("target-1", "One");
        service.authorizeSyntheticTarget("target-2", "Two");
        service.registerSyntheticInvite("target-1", "offline-key");
        service.registerSyntheticInvite("target-1", "offline-key");
        assertThatThrownBy(() -> service.registerSyntheticInvite("target-2", "offline-key"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> service.registerSyntheticInvite("target-1", "https://t.me/+not-an-invite"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("offline identifier");
    }

    @Test
    void materialGenerationCopiesOnlySuppliedBodiesAndDoesNotInventNews() {
        var materials = service.createMaterials(List.of("Verified supplied fact A.", "Verified supplied fact B."));
        assertThat(materials.synthetic()).isTrue();
        assertThat(materials.intro()).startsWith(GrowthService.SYNTHETIC_PREFIX);
        assertThat(materials.pinned()).startsWith(GrowthService.SYNTHETIC_PREFIX);
        assertThat(materials.dailyDigest()).endsWith("Verified supplied fact A.\n\nVerified supplied fact B.");
        assertThat(materials.promotion()).endsWith("Verified supplied fact A.\n\nVerified supplied fact B.");
        assertThat(service.createMaterials(List.of()).dailyDigest()).contains("No verified items supplied.");
        assertThatThrownBy(() -> service.createMaterials(List.of("x".repeat(4096))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MESSAGE_LIMIT");
    }

    @Test
    void realTargetsAreRejectedEvenIfDatabaseClaimsTheyAreAuthorized() {
        jdbc.update("INSERT INTO growth_target(target_id,label,synthetic,authorized,created_at,updated_at) VALUES('real','Real',FALSE,TRUE,0,0)");
        assertThatThrownBy(() -> service.distribute("op", "real", text("report")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REAL_GROWTH_TARGET_UNSUPPORTED");
        assertThatThrownBy(() -> service.authorizeSyntheticTarget("real", "Real"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REAL_GROWTH_TARGET_UNSUPPORTED");
        assertThat(service.report().distributions()).isZero();
    }

    @Test
    void concurrentDuplicateDistributionsAndJoinsCommitOnlyOnce() throws Exception {
        service.authorizeSyntheticTarget("target-1", "One");
        service.registerSyntheticInvite("target-1", "offline-key");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var one = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.distribute("concurrent-key", "target-1", text("same"));
            });
            var two = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.distribute("concurrent-key", "target-1", text("same"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(two.get(10, TimeUnit.SECONDS));
            var joinOne = executor.submit(() -> service.recordJoin(1, "offline-key"));
            var joinTwo = executor.submit(() -> service.recordJoin(1, "offline-key"));
            assertThat(joinOne.get(10, TimeUnit.SECONDS)).isEqualTo(joinTwo.get(10, TimeUnit.SECONDS));
        }
        assertThat(service.report().distributions()).isEqualTo(1);
        assertThat(service.report().knownJoins()).isEqualTo(1);
    }

    private static String text(String body) { return GrowthService.SYNTHETIC_PREFIX + "\n" + body; }
}
