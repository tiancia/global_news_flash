package com.globalnewsflash.growth.application;

import static com.globalnewsflash.shared.Hashing.sha256;

import com.globalnewsflash.growth.domain.Distribution;
import com.globalnewsflash.growth.domain.GrowthMaterials;
import com.globalnewsflash.growth.domain.GrowthPolicy;
import com.globalnewsflash.growth.domain.GrowthReport;
import com.globalnewsflash.growth.domain.JoinResult;
import com.globalnewsflash.growth.port.in.GrowthUseCase;
import com.globalnewsflash.growth.port.out.GrowthRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Local simulation only. This class has no messaging or invitation client. */
@Service
public class GrowthService implements GrowthUseCase {
    public static final String SYNTHETIC_PREFIX = GrowthPolicy.SYNTHETIC_PREFIX;
    private final GrowthRepository mapper;
    private final Clock clock;

    public GrowthService(GrowthRepository mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void authorizeSyntheticTarget(String targetId, String label) {
        GrowthPolicy.requireKey(targetId, "targetId");
        GrowthPolicy.requireText(label, "label", 256);
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
        GrowthPolicy.requireTarget(target, label);
        mapper.setAuthorization(targetId, true, clock.millis());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revokeTarget(String targetId) {
        GrowthPolicy.requireKey(targetId, "targetId");
        var target = mapper.lockTarget(targetId);
        GrowthPolicy.requireRevocable(target);
        mapper.setAuthorization(targetId, false, clock.millis());
    }

    /** Callers supply already-validated bodies. No facts or impact interpretations are generated here. */
    public GrowthMaterials createMaterials(List<String> verifiedBodies) {
        return GrowthPolicy.createMaterials(verifiedBodies);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Distribution distribute(String operationKey, String targetId, String text) {
        GrowthPolicy.requireKey(operationKey, "operationKey");
        GrowthPolicy.requireKey(targetId, "targetId");
        GrowthPolicy.requireDistribution(text);
        String hash = sha256(text);
        var existing = mapper.distribution(operationKey);
        if (existing != null) return GrowthPolicy.requireSame(existing, targetId, hash);
        requireAuthorizedTarget(targetId);
        // Locking the target also serializes revocation against the simulated delivery commit.
        existing = mapper.distribution(operationKey);
        if (existing != null) return GrowthPolicy.requireSame(existing, targetId, hash);
        var result = new Distribution(operationKey, targetId, text, hash, "SIMULATED", true, clock.millis());
        try {
            mapper.insertDistribution(result);
            return result;
        } catch (DuplicateKeyException exception) {
            existing = mapper.distribution(operationKey);
            if (existing == null) throw exception;
            return GrowthPolicy.requireSame(existing, targetId, hash);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void registerSyntheticInvite(String targetId, String inviteKey) {
        GrowthPolicy.requireKey(targetId, "targetId");
        GrowthPolicy.requireKey(inviteKey, "inviteKey");
        requireAuthorizedTarget(targetId);
        var existing = mapper.invite(inviteKey);
        if (existing != null) {
            GrowthPolicy.requireSameInvite(existing, targetId);
            return;
        }
        try { mapper.insertInvite(inviteKey, targetId, clock.millis()); }
        catch (DuplicateKeyException exception) {
            existing = mapper.invite(inviteKey);
            if (existing == null) throw exception;
            GrowthPolicy.requireSameInvite(existing, targetId);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JoinResult recordJoin(long updateId, String inviteKey) {
        GrowthPolicy.requireJoin(updateId, inviteKey);
        var existing = mapper.join(updateId);
        if (existing != null) return GrowthPolicy.requireSameJoin(existing, inviteKey);
        var invite = inviteKey == null ? null : mapper.invite(inviteKey);
        var target = invite != null && invite.synthetic() ? mapper.lockTarget(invite.targetId()) : null;
        var result = GrowthPolicy.attribute(updateId, inviteKey, invite, target, clock.millis());
        try {
            mapper.insertJoin(result);
            return result;
        } catch (DuplicateKeyException exception) {
            existing = mapper.join(updateId);
            if (existing == null) throw exception;
            return GrowthPolicy.requireSameJoin(existing, inviteKey);
        }
    }

    @Transactional(readOnly = true)
    public GrowthReport report() {
        return new GrowthReport(mapper.knownJoins(), mapper.unknownJoins(), mapper.distributionCount(), true);
    }

    private void requireAuthorizedTarget(String targetId) {
        GrowthPolicy.requireAuthorized(mapper.lockTarget(targetId));
    }
}
