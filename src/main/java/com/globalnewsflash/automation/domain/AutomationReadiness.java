package com.globalnewsflash.automation.domain;

import java.time.Duration;
import java.util.*;

/** Evaluates operating inputs without depending on configuration beans or network clients. */
public record AutomationReadiness(boolean live, List<String> operatingProblems, List<String> aiProblems,
        Long requestEstimate, boolean invalidEstimate, long dailyBudgetMicros, boolean ingestionEnabled,
        boolean approvedSourceEnabled, String target, Duration jobLease, Duration aiTimeout, long inboxLeaseMillis,
        Duration cycleLease, long sourceTimeoutMillis) {
    public List<String> reasons() {
        var reasons = new ArrayList<String>();
        if (!live) reasons.add("LIVE_EXTERNAL_ACCESS_DISABLED");
        reasons.addAll(operatingProblems);
        reasons.addAll(aiProblems);
        if (invalidEstimate) reasons.add("AI_COST_LIMIT_INVALID");
        else if (requestEstimate != null && requestEstimate > dailyBudgetMicros) reasons.add("REQUEST_RESERVATION_EXCEEDS_DAILY_BUDGET");
        if (!ingestionEnabled) reasons.add("INGESTION_DISABLED");
        if (live && !approvedSourceEnabled) reasons.add("NO_APPROVED_SOURCE_ENABLED");
        if (target == null || target.isBlank()) reasons.add("TELEGRAM_TARGET_MISSING");
        if (jobLease != null && aiTimeout != null && jobLease.compareTo(aiTimeout.plusSeconds(30)) <= 0) reasons.add("EDITOR_LEASE_TOO_SHORT");
        if (jobLease != null && inboxLeaseMillis <= jobLease.toMillis()) reasons.add("INBOX_LEASE_TOO_SHORT");
        if (cycleLease != null && cycleLease.toMillis() <= sourceTimeoutMillis + 30_000L) reasons.add("CYCLE_LEASE_TOO_SHORT");
        return List.copyOf(reasons);
    }
}
