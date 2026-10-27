package com.globalnewsflash.automation.domain;

import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.publication.domain.PublicationOperation;
import java.util.*;

public final class PublicationEvidence {
    private PublicationEvidence() {}
    public static boolean eligible(EventVersion version) { return version != null && "ELIGIBLE".equals(version.editorialState()); }
    public static boolean accounted(Reservation reservation) { return reservation != null && "SETTLED".equals(reservation.state()); }
    public static boolean current(Integer latest, int version) { return Objects.equals(latest, version); }
    public static boolean matches(PublicationOperation operation, EventVersion version, EditorialResult decision) {
        return !(operation.synthetic() && !"SEND".equals(operation.kind())) && decision != null && decision.draft() != null
                && operation.body().equals(decision.draft().body()) && Objects.equals(version.publicationId(), operation.id());
    }
    public static boolean unchangedSources(List<SourcePolicy> previous, List<SourcePolicy> current) {
        return previous.stream().allMatch(p -> current.stream().anyMatch(p::equals));
    }
}
