package com.globalnewsflash.publication.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Engineering bounds for offline delivery; these are not approved production policy. */
@Component
@ConfigurationProperties("news.publication")
public final class PublicationProperties {
    private Duration lease = Duration.ofSeconds(60);
    private int maxAttempts = 3;

    public Duration getLease() { return lease; }
    public void setLease(Duration lease) { this.lease = lease; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
