package com.globalnewsflash.publication.infrastructure;

import com.globalnewsflash.publication.port.out.PublicationSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Transport retry bounds and conservative, configurable live publication cadence. */
@Component
@ConfigurationProperties("news.publication")
public final class PublicationProperties implements PublicationSettings {
    private Duration lease = Duration.ofSeconds(60);
    private int maxAttempts = 3;
    private int maxPerHour = 6;
    private int maxPerDay = 24;
    private Duration minimumInterval = Duration.ofMinutes(5);

    public Duration getLease() { return lease; }
    public void setLease(Duration lease) { this.lease = lease; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getMaxPerHour() { return maxPerHour; }
    public void setMaxPerHour(int maxPerHour) { this.maxPerHour = maxPerHour; }
    public int getMaxPerDay() { return maxPerDay; }
    public void setMaxPerDay(int maxPerDay) { this.maxPerDay = maxPerDay; }
    public Duration getMinimumInterval() { return minimumInterval; }
    public void setMinimumInterval(Duration minimumInterval) { this.minimumInterval = minimumInterval; }
}
