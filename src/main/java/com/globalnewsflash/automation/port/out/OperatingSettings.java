package com.globalnewsflash.automation.port.out;

import com.globalnewsflash.editorial.domain.EditorialPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface OperatingSettings {
    EditorialPolicy policy();
    List<String> problems();
    boolean isEnabled();
    boolean isPolicyReady();
    String getPolicyVersion();
    Duration getMaxAge();
    int getMaxCharacters();
    int getMinimumIndependentChains();
    Set<String> getAllowedTopics();
    int getBatchSize();
    long getDailyBudgetMicros();
    int getDailyRequests();
    Duration getJobLease();
    Duration getCycleLease();
    String getRevisionMode();
}
