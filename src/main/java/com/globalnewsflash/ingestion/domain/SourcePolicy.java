package com.globalnewsflash.ingestion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit admission applies to a producer and license version, not merely an endpoint. */
public record SourcePolicy(String sourceId, boolean enabled, boolean approved, boolean synthetic,
                           String licenseVersion, Set<String> permittedProducers) {
    public SourcePolicy {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        permittedProducers = Set.copyOf(Objects.requireNonNull(permittedProducers, "permittedProducers"));
    }

    public List<String> check(SourceMaterial material) {
        Objects.requireNonNull(material, "material");
        var reasons = new ArrayList<String>();
        if (!sourceId.equals(material.sourceId())) reasons.add("SOURCE_MISMATCH");
        if (!enabled) reasons.add("SOURCE_DISABLED");
        if (!approved) reasons.add("SOURCE_NOT_APPROVED");
        if (licenseVersion == null || licenseVersion.isBlank()) reasons.add("LICENSE_NOT_RECORDED");
        if (synthetic != material.synthetic()) reasons.add("MATERIAL_MODE_MISMATCH");
        if (!permittedProducers.contains(material.producer())) reasons.add("PRODUCER_NOT_PERMITTED");
        return List.copyOf(reasons);
    }
}
