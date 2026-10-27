package com.globalnewsflash.growth.domain;

import java.util.List;
import java.util.Objects;

public final class GrowthPolicy {
    public static final String SYNTHETIC_PREFIX = "[SYNTHETIC OFFLINE]";
    private GrowthPolicy() {}

    public static GrowthMaterials createMaterials(List<String> verifiedBodies) {
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

    public static Distribution requireSame(Distribution distribution, String targetId, String hash) {
        if (!distribution.synthetic() || !distribution.targetId().equals(targetId) || !distribution.bodyHash().equals(hash)) {
            throw new IllegalArgumentException("DISTRIBUTION_IDEMPOTENCY_CONFLICT");
        }
        return distribution;
    }

    public static void requireSameInvite(Invite invite, String targetId) {
        if (!invite.synthetic() || !invite.targetId().equals(targetId)) {
            throw new IllegalArgumentException("INVITE_IDEMPOTENCY_CONFLICT");
        }
    }

    public static JoinResult requireSameJoin(JoinResult join, String inviteKey) {
        if (!join.synthetic() || !Objects.equals(join.inviteKey(), inviteKey)) {
            throw new IllegalArgumentException("JOIN_UPDATE_IDEMPOTENCY_CONFLICT");
        }
        return join;
    }

    public static void requireKey(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException(name + " must be a bounded offline identifier, not a URL");
        }
    }

    public static void requireText(String value, String name, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(name + " is empty or exceeds the input limit");
        }
    }


    public static void requireAuthorized(Target target) {
        if (target == null || !target.authorized()) throw new IllegalArgumentException("GROWTH_TARGET_NOT_AUTHORIZED");
        if (!target.synthetic()) throw new IllegalArgumentException("REAL_GROWTH_TARGET_UNSUPPORTED");
    }

    public static void requireTarget(Target target, String label) {
        if (!target.synthetic()) throw new IllegalArgumentException("REAL_GROWTH_TARGET_UNSUPPORTED");
        if (!target.label().equals(label)) throw new IllegalArgumentException("TARGET_LABEL_MISMATCH");
    }

    public static void requireRevocable(Target target) {
        if (target == null) throw new IllegalArgumentException("GROWTH_TARGET_NOT_FOUND");
        if (!target.synthetic()) throw new IllegalArgumentException("REAL_GROWTH_TARGET_UNSUPPORTED");
    }

    public static void requireDistribution(String text) {
        requireText(text, "distribution text", 4096);
        if (!text.startsWith(SYNTHETIC_PREFIX)) throw new IllegalArgumentException("NON_SYNTHETIC_GROWTH_CONTENT");
    }

    public static void requireJoin(long updateId, String inviteKey) {
        if (updateId < 0) throw new IllegalArgumentException("updateId must be nonnegative");
        if (inviteKey != null && inviteKey.length() > 512) throw new IllegalArgumentException("inviteKey exceeds input limit");
    }

    public static JoinResult attribute(long updateId, String inviteKey, Invite invite, Target target, long now) {
        String targetId = null;
        String attribution = "UNKNOWN";
        String reason = inviteKey == null || inviteKey.isBlank() ? "MISSING_INVITE" : "UNRECOGNIZED_INVITE";
        if (invite != null && invite.synthetic()) {
            if (target != null && target.synthetic() && target.authorized()) {
                targetId = target.targetId();
                attribution = "KNOWN";
                reason = "MATCHED_SYNTHETIC_INVITE";
            } else reason = "TARGET_REVOKED_OR_UNAVAILABLE";
        }
        return new JoinResult(updateId, inviteKey, targetId, attribution, reason, true, now);
    }
}
