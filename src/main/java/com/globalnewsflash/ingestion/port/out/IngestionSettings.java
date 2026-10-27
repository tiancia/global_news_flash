package com.globalnewsflash.ingestion.port.out;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface IngestionSettings {
    boolean isEnabled();
    long getPollDelayMs();
    long getSourcePollIntervalMs();
    int getMaxSourcesPerPoll();
    int getRequestTimeoutSeconds();
    int getSourceLeaseSeconds();
    int getInboxLeaseSeconds();
    int getMaxPendingItems();
    int getMaxItemsPerSource();
    int getMaxResponseBytes();
    int getMaxBodyCharacters();
    int getMinimumBodyCharacters();
}
