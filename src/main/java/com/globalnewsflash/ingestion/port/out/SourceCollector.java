package com.globalnewsflash.ingestion.port.out;

import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceSpec;
import java.time.Instant;
import java.util.List;

/** Collects an immutable source snapshot; no database or inbox state is changed here. */
public interface SourceCollector {
    class CollectionFailure extends RuntimeException {
        private final String code;
        public CollectionFailure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    class HeartbeatFailure extends RuntimeException {
        public HeartbeatFailure() { super("AUTOMATION_HEARTBEAT_FAILED"); }
    }
    record Snapshot(String payload, String etag, String lastModified, boolean notModified) {}
    record Batch(List<SourceMaterial> materials, String etag, String lastModified,
            String cursor, boolean notModified, int nextOffset, boolean complete) {}

    Snapshot open(SourceSpec source, String etag, String lastModified, Instant discoveredAt, Runnable heartbeat);
    Batch collect(SourceSpec source, SourcePolicy policy, Snapshot snapshot, int offset, Instant discoveredAt, Runnable heartbeat);
}
