package com.globalnewsflash.digest.domain;

import com.globalnewsflash.publication.domain.PublicationOperation;
import java.time.*;
import java.util.List;

public final class DigestPublicationPolicy {
    private DigestPublicationPolicy() {}

    public static boolean matches(PublicationOperation operation, DigestRecord record, String target) {
        return !operation.synthetic() && "SEND".equals(operation.kind()) && operation.eventVersion() == 1 && operation.originalOperationId() == null
                && record.target().equals(operation.target()) && record.target().equals(target) && record.eventKey().equals(operation.eventKey())
                && record.publicationId().equals(operation.id()) && record.body().equals(operation.body());
    }

    public static boolean expired(DigestRecord record, Instant now) {
        return !record.digestDate().equals(now.atZone(ZoneOffset.UTC).toLocalDate().toString()) || record.windowEnd() > now.toEpochMilli()
                || record.windowEnd() < now.minus(Duration.ofHours(24)).toEpochMilli() || record.windowStart() != record.windowEnd() - Duration.ofHours(24).toMillis();
    }

    public static boolean validSnapshot(DigestRecord record, List<DigestItem> items, List<String> ids, int maxItems, int maxCharacters) {
        return !items.isEmpty() && items.size() <= maxItems && ids.equals(items.stream().map(DigestItem::operationId).toList())
                && DigestRenderer.eventKey(record.digestDate(), record.target()).equals(record.eventKey())
                && DigestRenderer.render(record.digestDate(), items).equals(record.body()) && DigestRenderer.length(record.body()) <= maxCharacters;
    }
}
