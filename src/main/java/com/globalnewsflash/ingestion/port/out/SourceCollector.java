package com.globalnewsflash.ingestion.port.out;

import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceSpec;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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
    record Snapshot(String payload, String etag, String lastModified, boolean notModified, Set<String> excludedItems) {
        public Snapshot(String payload, String etag, String lastModified, boolean notModified) {
            this(payload, etag, lastModified, notModified, Set.of());
        }
        public Snapshot { excludedItems = Set.copyOf(excludedItems); }
    }
    record ItemFailure(String itemKey, String code) {}
    record Batch(List<SourceMaterial> materials, String etag, String lastModified,
            String cursor, boolean notModified, int nextOffset, boolean complete, List<ItemFailure> failures) {
        public Batch(List<SourceMaterial> materials, String etag, String lastModified,
                String cursor, boolean notModified, int nextOffset, boolean complete) {
            this(materials, etag, lastModified, cursor, notModified, nextOffset, complete, List.of());
        }
    }

    Snapshot open(SourceSpec source, String etag, String lastModified, Instant discoveredAt, Runnable heartbeat);
    Batch collect(SourceSpec source, SourcePolicy policy, Snapshot snapshot, int offset, Instant discoveredAt, Runnable heartbeat);
}
