package com.globalnewsflash.automation.port.out;

import com.globalnewsflash.automation.domain.Alert;
import com.globalnewsflash.automation.domain.EditorJob;
import com.globalnewsflash.automation.domain.Run;
import java.util.List;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface LiveAutomationRepository {
    EditorJob lockJob(String key, String fingerprint);
    EditorJob lockJobId(String id);
    EditorJob findJob(String id);
    void insertJob(EditorJob job);
    void finishJob(String id, String state, String error, long now);
    int restartJob(String id, String reservation, long until, long now);
    void restartVersion(String key, int version, String decision, String reservation);
    List<EditorJob> expiredJobs(long now);
    List<EditorJob> jobs();
    void finishVersion(String key, int version, String state, String decision, String publicationId);
    int claimCycle(String owner, long until, long now);
    int renewCycle(String owner, long until, long now);
    int releaseCycle(String owner);
    void startRun(String id, long now);
    void finishRun(String id, long now, String state, int processed, String error);
    List<Run> runs();
    void insertAlert(Alert alert);
    List<Alert> alerts();
    List<Alert> unnotifiedAlerts();
}
