package com.globalnewsflash.growth.port.in;

import com.globalnewsflash.growth.domain.Distribution;
import com.globalnewsflash.growth.domain.GrowthMaterials;
import com.globalnewsflash.growth.domain.GrowthReport;
import com.globalnewsflash.growth.domain.JoinResult;
import java.util.List;

/** Public use cases of the growth module. */
public interface GrowthUseCase {

    void authorizeSyntheticTarget(String targetId, String label);
    void revokeTarget(String targetId);
    GrowthMaterials createMaterials(List<String> verifiedBodies);
    Distribution distribute(String operationKey, String targetId, String text);
    void registerSyntheticInvite(String targetId, String inviteKey);
    JoinResult recordJoin(long updateId, String inviteKey);
    GrowthReport report();
}
