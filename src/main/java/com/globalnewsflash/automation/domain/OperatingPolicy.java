package com.globalnewsflash.automation.domain;

import com.globalnewsflash.editorial.domain.EditorialPolicy;
import java.time.Duration;
import java.util.*;

public record OperatingPolicy(EditorialPolicy editorial, int batchSize, long dailyBudgetMicros, int dailyRequests,
        Duration jobLease, Duration cycleLease, String revisionMode) {
    public List<String> problems() {
        var reasons = new ArrayList<String>();
        if (!editorial.configured()) reasons.add("OPERATING_POLICY_NOT_READY");
        if (editorial.maxCharacters() > 4096 || editorial.maxCharacters() < 200) reasons.add("INVALID_MESSAGE_LENGTH");
        if (batchSize < 1 || batchSize > 100) reasons.add("INVALID_BATCH_SIZE");
        if (dailyBudgetMicros <= 0 || dailyRequests <= 0) reasons.add("INVALID_DAILY_BUDGET");
        if (jobLease == null || jobLease.compareTo(Duration.ofSeconds(30)) < 0 || cycleLease == null || cycleLease.compareTo(jobLease) <= 0)
            reasons.add("INVALID_AUTOMATION_LEASE");
        if (revisionMode == null || !Set.of("HOLD", "EDIT", "UPDATE", "CORRECTION").contains(revisionMode)) reasons.add("INVALID_REVISION_MODE");
        return List.copyOf(reasons);
    }
}
