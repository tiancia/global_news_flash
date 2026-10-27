package com.globalnewsflash.automation.port.out;

import com.globalnewsflash.automation.domain.EventVersion;
import java.util.List;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface PipelineRepository {
    void insertEvent(String key);
    Integer lockEvent(String key);
    void updateVersion(String key, int version);
    EventVersion findVersion(String key, String fingerprint);
    EventVersion findNumber(String key, int version);
    Integer latestVersion(String key);
    int publicationCount(String key);
    void insertVersion(EventVersion version);
    List<EventVersion> versions();
    String findMaterial(String id);
    void insertMaterial(String id, String source, String event, String fingerprint, String payload, long discovered, boolean synthetic);
    void audit(String id, String action, String subject, String details, long created);
    long materialCount();
    long auditCount();
}
