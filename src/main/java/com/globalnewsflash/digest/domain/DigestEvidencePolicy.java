package com.globalnewsflash.digest.domain;

import com.globalnewsflash.automation.domain.EventVersion;
import com.globalnewsflash.automation.domain.Reservation;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.publication.domain.PublicationOperation;
import java.time.*;
import java.util.*;

public final class DigestEvidencePolicy {
    private DigestEvidencePolicy() {}

    public static boolean validWindow(List<DigestItem> items, String target, Instant start, Instant end, Instant now) {
        return items != null && !items.isEmpty() && items.size() <= 500 && target != null && start != null && end != null
                && start.isBefore(end) && !end.isAfter(now);
    }

    public static boolean currentItem(DigestItem item, String target, Instant start, Instant end, Instant now,
            PublicationOperation operation, Instant savedSentAt, Integer latest) {
        if (item.operationId() == null || item.eventKey() == null || item.eventKey().startsWith("digest:") || item.body() == null
                || item.body().isBlank() || item.sentAt() == null || item.sentAt().isBefore(start) || item.sentAt().isAfter(end)
                || item.sentAt().isBefore(now.minus(Duration.ofHours(24)))) return false;
        return operation != null && !operation.synthetic() && "SENT".equals(operation.state()) && operation.messageId() != null
                && !operation.messageId().isBlank() && !operation.messageId().startsWith("sim-") && target.equals(operation.target())
                && item.eventKey().equals(operation.eventKey()) && item.eventVersion() == operation.eventVersion()
                && item.body().equals(operation.body()) && item.sentAt().equals(savedSentAt) && Objects.equals(latest, item.eventVersion());
    }

    public static boolean accountedVersion(EventVersion version, Reservation reservation, String operationId) {
        return version != null && "ELIGIBLE".equals(version.editorialState()) && operationId.equals(version.publicationId())
                && reservation != null && "SETTLED".equals(reservation.state());
    }

    public static boolean consistentSnapshot(DigestItem item, EditorialResult decision, List<SourceMaterial> materials,
            List<SourcePolicy> previousSources, List<SourcePolicy> currentSources) {
        return decision.eligible() && decision.draft() != null && !decision.draft().synthetic() && item.body().equals(decision.draft().body())
                && !materials.isEmpty() && materials.stream().noneMatch(m -> m.synthetic() || !item.eventKey().equals(m.eventKey()))
                && previousSources.stream().allMatch(previous -> currentSources.stream().anyMatch(previous::equals));
    }
}
