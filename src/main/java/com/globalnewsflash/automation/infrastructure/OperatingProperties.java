package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.editorial.domain.EditorialPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Conservative initial settings are editable operating inputs, not validated editorial policy. */
@ConfigurationProperties("news.automation")
public class OperatingProperties {
    private boolean enabled;
    private boolean policyReady;
    private String policyVersion = "initial-v1";
    private Duration maxAge = Duration.ofHours(12);
    private int maxCharacters = 3000;
    private int minimumIndependentChains = 1;
    private Set<String> allowedTopics = Set.of("EARTHQUAKE", "OFFICIAL_ANNOUNCEMENT", "WORLD", "ECONOMY", "SCIENCE", "TECHNOLOGY");
    private int batchSize = 10;
    private long dailyBudgetMicros = 1_000_000;
    private int dailyRequests = 100;
    private Duration jobLease = Duration.ofMinutes(3);
    private Duration cycleLease = Duration.ofMinutes(15);
    private String revisionMode = "HOLD";

    public EditorialPolicy policy() {
        return new EditorialPolicy(policyVersion, maxAge, maxCharacters, minimumIndependentChains, allowedTopics, policyReady);
    }

    public List<String> problems() {
        var reasons = new java.util.ArrayList<String>();
        if (!policy().configured()) reasons.add("OPERATING_POLICY_NOT_READY");
        if (maxCharacters > 4096 || maxCharacters < 200) reasons.add("INVALID_MESSAGE_LENGTH");
        if (batchSize < 1 || batchSize > 100) reasons.add("INVALID_BATCH_SIZE");
        if (dailyBudgetMicros <= 0 || dailyRequests <= 0) reasons.add("INVALID_DAILY_BUDGET");
        if (jobLease == null || jobLease.compareTo(Duration.ofSeconds(30)) < 0
                || cycleLease == null || cycleLease.compareTo(jobLease) <= 0) reasons.add("INVALID_AUTOMATION_LEASE");
        if (revisionMode == null || !Set.of("HOLD", "EDIT", "UPDATE", "CORRECTION").contains(revisionMode)) reasons.add("INVALID_REVISION_MODE");
        return List.copyOf(reasons);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isPolicyReady() { return policyReady; }
    public void setPolicyReady(boolean value) { policyReady = value; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion = value; }
    public Duration getMaxAge() { return maxAge; }
    public void setMaxAge(Duration value) { maxAge = value; }
    public int getMaxCharacters() { return maxCharacters; }
    public void setMaxCharacters(int value) { maxCharacters = value; }
    public int getMinimumIndependentChains() { return minimumIndependentChains; }
    public void setMinimumIndependentChains(int value) { minimumIndependentChains = value; }
    public Set<String> getAllowedTopics() { return allowedTopics; }
    public void setAllowedTopics(Set<String> value) { allowedTopics = value == null ? Set.of() : Set.copyOf(value); }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public long getDailyBudgetMicros() { return dailyBudgetMicros; }
    public void setDailyBudgetMicros(long value) { dailyBudgetMicros = value; }
    public int getDailyRequests() { return dailyRequests; }
    public void setDailyRequests(int value) { dailyRequests = value; }
    public Duration getJobLease() { return jobLease; }
    public void setJobLease(Duration value) { jobLease = value; }
    public Duration getCycleLease() { return cycleLease; }
    public void setCycleLease(Duration value) { cycleLease = value; }
    public String getRevisionMode() { return revisionMode; }
    public void setRevisionMode(String value) { revisionMode = value; }
}
