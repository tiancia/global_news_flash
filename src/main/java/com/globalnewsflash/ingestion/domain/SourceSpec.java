package com.globalnewsflash.ingestion.domain;

import java.util.Set;

public record SourceSpec(String id, String type, String url, boolean enabled, boolean approved, String license,
            String producer, Set<String> permittedProducers, Set<String> allowedHosts, String articleSelector,
            String topic, boolean fetchArticle) {}
