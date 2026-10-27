package com.globalnewsflash.growth.port.out;

import com.globalnewsflash.growth.domain.Distribution;
import com.globalnewsflash.growth.domain.Invite;
import com.globalnewsflash.growth.domain.JoinResult;
import com.globalnewsflash.growth.domain.Target;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface GrowthRepository {
    Target lockTarget(String targetId);
    int insertTarget(String targetId, String label, long now);
    int setAuthorization(String targetId, boolean authorized, long now);
    Distribution distribution(String operationKey);
    int insertDistribution(Distribution distribution);
    Invite invite(String inviteKey);
    int insertInvite(String inviteKey, String targetId, long now);
    JoinResult join(long updateId);
    int insertJoin(JoinResult join);
    long knownJoins();
    long unknownJoins();
    long distributionCount();
}
