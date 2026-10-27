package com.globalnewsflash.ingestion.domain;

public record SourceRegistration(String id, String sourceId, boolean enabled, boolean approved, boolean synthetic,
                     String licenseVersion, String permittedProducers, String scopeDescription) {
    public SourcePolicy policy() {
        return new SourcePolicy(sourceId, enabled, approved, synthetic, licenseVersion,
                permittedProducers.isBlank() ? java.util.Set.of() : java.util.Set.copyOf(java.util.Arrays.asList(permittedProducers.split(","))));
    }

    public void requireEnablement(boolean enabled, boolean live) {
        if (enabled && !synthetic && (!live || !approved || licenseVersion.isBlank() || "pending".equals(licenseVersion) || permittedProducers.isBlank()))
            throw new IllegalArgumentException("Real sources require live mode, approval, license and permitted producers");
    }
}
