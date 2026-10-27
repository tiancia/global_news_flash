package com.globalnewsflash.publication.port.out;

import java.time.Duration;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface PublicationSettings {
    Duration getLease();
    int getMaxAttempts();
    int getMaxPerHour();
    int getMaxPerDay();
    Duration getMinimumInterval();
}
