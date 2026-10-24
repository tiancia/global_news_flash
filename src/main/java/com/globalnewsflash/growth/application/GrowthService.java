package com.globalnewsflash.growth.application;

import com.globalnewsflash.growth.domain.Distribution;
import com.globalnewsflash.growth.domain.GrowthMaterials;
import com.globalnewsflash.growth.domain.GrowthReport;
import com.globalnewsflash.growth.domain.JoinResult;
import com.globalnewsflash.growth.infrastructure.GrowthMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Local simulation only. This class has no messaging or invitation client. */
@Service
public class GrowthService {
    public static final String SYNTHETIC_PREFIX = "[SYNTHETIC OFFLINE]";
    private final GrowthMapper mapper;
    private final Clock clock;

    public GrowthService(GrowthMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void authorizeSyntheticTarget(String targetId, String label) {
        requireKey(targetId, "targetId");
        requireText(label, "label", 256);
        var target = mapper.lockTarget(targetId);
        if (target == null) {
            try {
                mapper.insertTarget(targetId, label, clock.millis());
                return;
            } catch (DuplicateKeyException exception) {
                target = mapper.lockTarget(targetId);
                if (target == null) throw exception;
            }
        }
        if (!target.synthetic()) throw new IllegalArgumentException("REAL_GROWTH_TARGET_UNSUPPORTED");
        if (!target.label().equals(label)) throw new IllegalArgumentException("TARGET_LABEL_MISMATCH");
        mapper.setAuthorization(targetId, true, clock.millis());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revokeTarget(String targetId) {
        requireKey(targetId, "targetId");
        var target = mapper.lockTarget(targetId);
        if (target == null) throw new IllegalArgumentException("GROWTH_TARGET_NOT_FOUND");
        if (!target.synthetic()) throw new IllegalArgumentException("REAL_GROWTH_TARGET_UNSUPPORTED");
        mapper.setAuthorization(targetId, false, clock.millis());
    }

    /** Callers supply already-validated bodies. No facts or impact interpretations are generated here. */
    public GrowthMaterials createMaterials(List<String> verifiedBodies) {
        Objects.requireNonNull(verifiedBodies, "verifiedBodies");
        if (verifiedBodies.size() > 100) throw new IllegalArgumentException("MATERIAL_INPUT_LIMIT_EXCEEDED");
        List<String> bodies = List.copyOf(verifiedBodies);
        long totalLength = 0;
        for (String body : bodies) {
            requireText(body, "verified body", 16_000);
            totalLength += body.length();
        }
        if (totalLength > 100_000) throw new IllegalArgumentException("MATERIAL_INPUT_LIMIT_EXCEEDED");
        String intro = SYNTHETIC_PREFIX + "\nGlobal News Flash\nImportant global developments, clearly explained, with sources.";
        String pinned = SYNTHETIC_PREFIX + "\nOffline demonstration. Sources accompany each item. Updates and corrections retain their history.";
        String dailyDigest = SYNTHETIC_PREFIX + "\nDaily share digest\n"
                + (bodies.isEmpty() ? "No verified items supplied." : String.join("\n\n", bodies));
        String promotion = SYNTHETIC_PREFIX + "\nGlobal News Flash: source-linked global developments.\n"
                + (bodies.isEmpty() ? "No verified items supplied." : String.join("\n\n", bodies));
        if (dailyDigest.length() > 4096 || promotion.length() > 4096) {
            throw new IllegalArgumentException("GROWTH_MATERIAL_EXCEEDS_MESSAGE_LIMIT");
        }
        return new GrowthMaterials(intro, pinned, dailyDigest, promotion, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Distribution distribute(String operationKey, String targetId, String text) {
        requireKey(operationKey, "operationKey");
        requireKey(targetId, "targetId");
        requireText(text, "distribution text", 4096);
        if (!text.startsWith(SYNTHETIC_PREFIX)) {
            throw new IllegalArgumentException("NON_SYNTHETIC_GROWTH_CONTENT");
        }
        String hash = sha256(text);
        var existing = mapper.distribution(operationKey);
        if (existing != null) return requireSame(existing, targetId, hash);
        requireAuthorizedTarget(targetId);
        // Locking the target also serializes revocation against the simulated delivery commit.
        existing = mapper.distribution(operationKey);
        if (existing != null) return requireSame(existing, targetId, hash);
        var result = new Distribution(operationKey, targetId, text, hash, "SIMULATED", true, clock.millis());
        try {
            mapper.insertDistribution(result);
            return result;
        } catch (DuplicateKeyException exception) {
            existing = mapper.distribution(operationKey);
            if (existing == null) throw exception;
            return requireSame(existing, targetId, hash);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void registerSyntheticInvite(String targetId, String inviteKey) {
        requireKey(targetId, "targetId");
        requireKey(inviteKey, "inviteKey");
        requireAuthorizedTarget(targetId);
        var existing = mapper.invite(inviteKey);
        if (existing != null) {
            requireSameInvite(existing, targetId);
            return;
        }
        try { mapper.insertInvite(inviteKey, targetId, clock.millis()); }
        catch (DuplicateKeyException exception) {
            existing = mapper.invite(inviteKey);
            if (existing == null) throw exception;
            requireSameInvite(existing, targetId);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JoinResult recordJoin(long updateId, String inviteKey) {
        if (updateId < 0) throw new IllegalArgumentException("updateId must be nonnegative");
        if (inviteKey != null && inviteKey.length() > 512) throw new IllegalArgumentException("inviteKey exceeds input limit");
        var existing = mapper.join(updateId);
        if (existing != null) return requireSameJoin(existing, inviteKey);
        var invite = inviteKey == null ? null : mapper.invite(inviteKey);
        String targetId = null;
        String attribution = "UNKNOWN";
        String reason = inviteKey == null || inviteKey.isBlank() ? "MISSING_INVITE" : "UNRECOGNIZED_INVITE";
        if (invite != null && invite.synthetic()) {
            var target = mapper.lockTarget(invite.targetId());
            if (target != null && target.synthetic() && target.authorized()) {
                targetId = target.targetId();
                attribution = "KNOWN";
                reason = "MATCHED_SYNTHETIC_INVITE";
            } else reason = "TARGET_REVOKED_OR_UNAVAILABLE";
        }
        var result = new JoinResult(updateId, inviteKey, targetId, attribution, reason, true, clock.millis());
        try {
            mapper.insertJoin(result);
            return result;
        } catch (DuplicateKeyException exception) {
            existing = mapper.join(updateId);
            if (existing == null) throw exception;
            return requireSameJoin(existing, inviteKey);
        }
    }

    @Transactional(readOnly = true)
    public GrowthReport report() {
        return new GrowthReport(mapper.knownJoins(), mapper.unknownJoins(), mapper.distributionCount(), true);
    }

    private void requireAuthorizedTarget(String targetId) {
        var target = mapper.lockTarget(targetId);
        if (target == null || !target.authorized()) throw new IllegalArgumentException("GROWTH_TARGET_NOT_AUTHORIZED");
        if (!target.synthetic()) throw new IllegalArgumentException("REAL_GROWTH_TARGET_UNSUPPORTED");
    }

    private static Distribution requireSame(Distribution distribution, String targetId, String hash) {
        if (!distribution.synthetic() || !distribution.targetId().equals(targetId) || !distribution.bodyHash().equals(hash)) {
            throw new IllegalArgumentException("DISTRIBUTION_IDEMPOTENCY_CONFLICT");
        }
        return distribution;
    }

    private static void requireSameInvite(GrowthMapper.Invite invite, String targetId) {
        if (!invite.synthetic() || !invite.targetId().equals(targetId)) {
            throw new IllegalArgumentException("INVITE_IDEMPOTENCY_CONFLICT");
        }
    }

    private static JoinResult requireSameJoin(JoinResult join, String inviteKey) {
        if (!join.synthetic() || !Objects.equals(join.inviteKey(), inviteKey)) {
            throw new IllegalArgumentException("JOIN_UPDATE_IDEMPOTENCY_CONFLICT");
        }
        return join;
    }

    private static void requireKey(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException(name + " must be a bounded offline identifier, not a URL");
        }
    }

    private static void requireText(String value, String name, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(name + " is empty or exceeds the input limit");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 implementation unavailable", exception);
        }
    }
}
