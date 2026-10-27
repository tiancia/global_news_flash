package com.globalnewsflash.ingestion.port.out;

import com.globalnewsflash.ingestion.domain.SourceSpec;
import java.util.List;

/** Explicitly configured sources, decoded by the configuration adapter. */
public interface SourceCatalog {
    List<SourceSpec> sources();
}
