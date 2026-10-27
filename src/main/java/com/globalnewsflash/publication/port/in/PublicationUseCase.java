package com.globalnewsflash.publication.port.in;

import com.globalnewsflash.publication.domain.DeliveryAttempt;
import com.globalnewsflash.publication.domain.PublicationAlert;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.domain.PublicationResolution;
import java.util.List;

/** Public use cases of the publication module. */
public interface PublicationUseCase {

    PublicationOperation enqueue(String eventKey, int eventVersion, String target, String kind, String body, String originalOperationId, boolean synthetic);
    PublicationOperation get(String id);
    List<PublicationOperation> list();
    List<DeliveryAttempt> attempts(String id);
    List<PublicationAlert> alerts();
    List<PublicationAlert> unnotifiedAlerts();
    List<PublicationResolution> resolutions(String id);
    PublicationOperation resolveUnknown(String id, String resolution, String messageId, String note);
    int processDueBatch();
    void setPaused(boolean paused);
    boolean isPaused();
    PublicationOperation dispatch(String id);
    int recoverExpired();
    String previewText(String id);
    PublicationOperation latestConfirmed(String eventKey, String target);
}
