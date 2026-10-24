package com.globalnewsflash.ingestion.application;

import com.globalnewsflash.ingestion.domain.SourceMaterial;
import java.time.Instant;
import java.util.List;

/** Parses supplied bytes only; collection and approval are separate application responsibilities. */
public interface SourceAdapter {
    String sourceId();
    List<SourceMaterial> parse(String payload, Instant discoveredAt, boolean synthetic);
}
