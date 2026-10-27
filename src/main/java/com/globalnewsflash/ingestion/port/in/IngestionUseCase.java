package com.globalnewsflash.ingestion.port.in;

import com.globalnewsflash.ingestion.domain.SourceMaterial;
import java.util.List;

/** Public use cases of the ingestion module. */
public interface IngestionUseCase {
    record InboxItem(String id, SourceMaterial material, int attempts) {}
    record SourcePoll(String sourceId, String state, int received, int stored, String code) {}
    record PollSummary(boolean active, int polled, int stored, int failed, long pending, List<SourcePoll> sources) {}
    record SourceStatus(String sourceId, String cursor, Long lastAttemptAt, Long lastSuccessAt,
            long nextPollAt, int consecutiveFailures, int lastItemCount, String errorCode,
            boolean snapshotPending, int snapshotOffset) {}
    record InboxStatus(String id, String sourceId, String eventKey, String state, String detail, int attempts,
            long createdAt, Long completedAt, long nextAttemptAt) {}
    boolean active();
    int syncSources();
    PollSummary poll();
    PollSummary poll(Runnable heartbeat);
    List<InboxItem> pending(int limit);
    void complete(String id, String state, String detail);
    void retry(String id, String code);
    List<SourceStatus> status();
    List<InboxStatus> inboxStatus();
    void requeue(String id);
}
