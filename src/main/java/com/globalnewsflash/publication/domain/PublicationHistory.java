package com.globalnewsflash.publication.domain;

import java.util.Comparator;
import java.util.List;

/** An amendment must refer to the latest confirmed real delivery for its event and channel. */
public final class PublicationHistory {
    private PublicationHistory() {}

    public static PublicationOperation latestConfirmed(List<PublicationOperation> operations, String eventKey, String target) {
        return operations.stream()
                .filter(operation -> !operation.synthetic() && operation.eventKey().equals(eventKey) && operation.target().equals(target))
                .filter(operation -> "SENT".equals(operation.state()))
                .max(Comparator.comparingInt(PublicationOperation::eventVersion)).orElse(null);
    }
}
