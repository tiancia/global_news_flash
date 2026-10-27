package com.globalnewsflash.automation.port.out;

import java.time.Duration;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface NotificationSettings {
    boolean configured();
    boolean isEnabled();
    Duration getTimeout();
}
