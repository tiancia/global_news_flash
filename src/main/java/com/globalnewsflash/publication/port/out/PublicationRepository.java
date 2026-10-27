package com.globalnewsflash.publication.port.out;

import com.globalnewsflash.publication.domain.ChannelLimit;
import com.globalnewsflash.publication.domain.DeliveryAttempt;
import com.globalnewsflash.publication.domain.PublicationAlert;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.domain.PublicationResolution;
import java.time.Instant;
import java.util.List;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface PublicationRepository {
    PublicationOperation find(String id);
    PublicationOperation lockFind(String id);
    List<PublicationOperation> list();
    List<PublicationOperation> due(Instant now);
    PublicationOperation findKey(String eventKey, int eventVersion, String target, String kind);
    int insert( PublicationOperation operation, Instant createdAt);
    boolean lockPaused();
    boolean paused();
    int setPaused(boolean paused);
    int auditPause(String id, boolean paused, Instant now);
    int claim(String id, String token, Instant until, Instant now);
    int beginSending(String id, String token, Instant now);
    int releaseClaim(String id, String token);
    int deferClaim(String id, String token, Instant retryAt);
    ChannelLimit lockChannelLimit(String target);
    int insertChannelLimit(String target, long hourStart, long dayStart, Instant now);
    int saveChannelLimit(ChannelLimit limit);
    int coolDownChannel(String target, Instant until);
    int cancelClaim(String id, String token);
    int insertAttempt(DeliveryAttempt attempt);
    int finish(String id, String token, String state, String messageId, Instant nextAttemptAt, String error, Instant now);
    int finishAttempt(String id, String token, String outcome, String error, Instant now);
    List<PublicationOperation> expired(Instant now);
    int recover(String id, String oldState, String token, String nextState, String error, Instant now);
    List<DeliveryAttempt> attempts(String id);
    int resolveUnknown(String id, String state, String messageId, String reason);
    int insertResolution(PublicationResolution resolution);
    List<PublicationResolution> resolutions(String id);
    int insertAlert(PublicationAlert alert);
    List<PublicationAlert> alerts();
    List<PublicationAlert> unnotifiedAlerts();
    int acknowledgeAlerts(String id, Instant now);
}
