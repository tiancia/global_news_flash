package com.globalnewsflash.editorial.port.out;

import com.globalnewsflash.editorial.domain.EditorResponse;
import java.time.Duration;
import java.util.List;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface ModelPricing {
    List<String> readinessReasons();
    long estimatedRequestMicros();
    long actualMicros(EditorResponse.Usage usage);
    Duration getTimeout();
}
