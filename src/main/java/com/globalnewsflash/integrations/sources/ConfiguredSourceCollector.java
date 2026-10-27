package com.globalnewsflash.integrations.sources;

import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceSpec;
import com.globalnewsflash.ingestion.port.out.IngestionSettings;
import com.globalnewsflash.ingestion.port.out.SourceCollector;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Lazily constructs live transport so offline startup does not require live-only settings. */
@Component
public final class ConfiguredSourceCollector implements SourceCollector {
    private final IngestionSettings properties;
    private final JsonMapper json;
    private LiveSourceCollector delegate;

    public ConfiguredSourceCollector(IngestionSettings properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    private synchronized LiveSourceCollector delegate() {
        if (delegate == null) delegate = new LiveSourceCollector(properties, json);
        return delegate;
    }

    public Snapshot open(SourceSpec source, String etag, String lastModified, Instant discoveredAt, Runnable heartbeat) {
        return delegate().open(source, etag, lastModified, discoveredAt, heartbeat);
    }

    public Batch collect(SourceSpec source, SourcePolicy policy, Snapshot snapshot, int offset, Instant discoveredAt, Runnable heartbeat) {
        return delegate().collect(source, policy, snapshot, offset, discoveredAt, heartbeat);
    }
}
