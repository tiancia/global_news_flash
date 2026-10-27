package com.globalnewsflash.publication.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record ChannelLimit(String target, long hourStart, int hourCount, long dayStart, int dayCount, Instant nextAllowedAt) {
    public record Decision(ChannelLimit reserved, Instant retryAt) {}

    public Decision reserve(Instant now, PublicationPolicy policy) {
        long hour = now.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        long day = now.truncatedTo(ChronoUnit.DAYS).getEpochSecond();
        int hourly = hourStart == hour ? hourCount : 0;
        int daily = dayStart == day ? dayCount : 0;
        Instant retry = nextAllowedAt.isAfter(now) ? nextAllowedAt : null;
        if (hourly >= policy.maxPerHour()) {
            Instant next = Instant.ofEpochSecond(hour).plus(1, ChronoUnit.HOURS);
            retry = retry == null || next.isAfter(retry) ? next : retry;
        }
        if (daily >= policy.maxPerDay()) {
            Instant next = Instant.ofEpochSecond(day).plus(1, ChronoUnit.DAYS);
            retry = retry == null || next.isAfter(retry) ? next : retry;
        }
        return retry != null ? new Decision(null, retry)
                : new Decision(new ChannelLimit(target, hour, hourly + 1, day, daily + 1, now.plus(policy.minimumInterval())), null);
    }
}
