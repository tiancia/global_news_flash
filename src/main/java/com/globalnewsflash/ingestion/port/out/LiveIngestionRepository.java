package com.globalnewsflash.ingestion.port.out;

import com.globalnewsflash.ingestion.domain.InboxRow;
import com.globalnewsflash.ingestion.domain.ArticleFailure;
import com.globalnewsflash.ingestion.domain.SourceRegistration;
import com.globalnewsflash.ingestion.domain.SourceState;
import java.util.List;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface LiveIngestionRepository {
    ArticleFailure articleFailure(String id);
    void insertArticleFailure(ArticleFailure failure);
    void updateArticleFailure(ArticleFailure failure);
    void recoverArticleFailure(String id, long now);
    List<ArticleFailure> articleFailures();
    List<String> quarantinedItems(String sourceId, String snapshotKey);
    void createSourceState(String sourceId);
    SourceState lockSourceState(String sourceId);
    List<SourceState> sourceStates();
    SourceRegistration lockSource(String sourceId);
    SourceRegistration source(String sourceId);
    void insertSource(SourceRegistration source);
    void updateSource(SourceRegistration source);
    void registered(String sourceId);
    void removeConfiguredSource(String sourceId);
    int leaseSource(String sourceId, String owner, long until, long now);
    int saveSnapshot(String sourceId, String owner, String payload, String etag, String modified, long createdAt, long now);
    int advanceSnapshot(String sourceId, String owner, int offset, long next, long now, int count);
    int finishSource(String sourceId, String owner, String cursor, String etag, String modified, long now, long next, int count);
    void failSource(String sourceId, String owner, long next, String code);
    String inboxId(String id);
    void insertInbox(InboxRow row);
    long pendingCount();
    List<InboxRow> lockDue( long now, int limit);
    void leaseInbox(String id, String owner, long until);
    InboxRow lockInbox(String id);
    int complete(String id, String owner, String state, String detail, long now);
    int retry(String id, String owner, String code, long next);
    List<InboxRow> recentInbox();
    void requeue(String id, long now);
}
