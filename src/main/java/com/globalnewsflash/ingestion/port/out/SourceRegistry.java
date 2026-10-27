package com.globalnewsflash.ingestion.port.out;

import com.globalnewsflash.ingestion.domain.SourceRegistration;
import java.util.List;

/** Current source admission declarations, including operator-controlled enablement. */
public interface SourceRegistry {
    List<SourceRegistration> sources();
    List<SourceRegistration> lockSources();
    int enableSource(String id, boolean enabled);
}
