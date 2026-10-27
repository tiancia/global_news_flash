package com.globalnewsflash.ingestion.application;

import static com.globalnewsflash.shared.Hashing.sha256;

import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.ingestion.domain.InboxRow;
import com.globalnewsflash.ingestion.domain.IngestionPolicy;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceRegistration;
import com.globalnewsflash.ingestion.domain.SourceSpec;
import com.globalnewsflash.ingestion.port.in.IngestionUseCase;
import com.globalnewsflash.ingestion.port.out.IngestionSettings;
import com.globalnewsflash.ingestion.port.out.LiveIngestionRepository;
import com.globalnewsflash.ingestion.port.out.SourceCatalog;
import com.globalnewsflash.ingestion.port.out.SourceCollector;
import com.globalnewsflash.shared.port.out.SnapshotCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable live collection. Network I/O is outside database transactions; cursor commits with the whole inbox batch. */
@Service
public class LiveIngestionService implements IngestionUseCase {

    private final IngestionSettings properties;
    private final RuntimeSettings runtime;
    private final LiveIngestionRepository mapper;
    private final SnapshotCodec json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SourceCollector collector;
    private final List<SourceSpec> sources;
    private final String owner = UUID.randomUUID().toString();

    public LiveIngestionService(IngestionSettings properties, RuntimeSettings runtime,
            LiveIngestionRepository mapper, SnapshotCodec json, Clock clock, PlatformTransactionManager transactionManager,
            SourceCollector collector, SourceCatalog catalog) {
        this.properties = properties;
        this.runtime = runtime;
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        this.sources = catalog.sources();
        this.collector = collector;
    }

    public boolean active() {
        return collector != null && properties.isEnabled() && "LIVE".equals(runtime.getMode()) && runtime.isExternalEnabled();
    }

    /**
     * First registration adopts explicit enabled/approved configuration. Later synchronization never re-enables a
     * DB source disabled by an operator; re-enable that source explicitly through the protected operations API.
     * Removing a managed source or setting its configured enabled=false disables it.
     */
    public synchronized int syncSources() {
        if (!active()) return 0;
        Integer count = transactions.execute(status -> {
            for (SourceSpec spec : sources) {
                try { mapper.createSourceState(spec.id()); }
                catch (DuplicateKeyException existing) { /* lock the durable row next */ }
                var state = mapper.lockSourceState(spec.id());
                SourceRegistration previous = mapper.lockSource(spec.id());
                boolean enabled = IngestionPolicy.enabledAfterSync(spec.enabled(), state.registered(), previous == null ? null : previous.enabled());
                var row = new SourceRegistration(spec.id() + ":real", spec.id(), enabled, spec.approved(), false,
                        spec.license(), String.join(",", spec.permittedProducers()),
                        "Explicit live " + spec.type() + " configuration; source passages require editorial validation");
                if (previous == null) mapper.insertSource(row);
                else mapper.updateSource(row);
                mapper.registered(spec.id());
            }
            Set<String> configured = sources.stream().map(SourceSpec::id).collect(java.util.stream.Collectors.toSet());
            for (var state : mapper.sourceStates()) {
                if (!configured.contains(state.sourceId())) mapper.removeConfiguredSource(state.sourceId());
            }
            return sources.size();
        });
        return count == null ? 0 : count;
    }

    public PollSummary poll() { return poll(() -> { }); }

    public synchronized PollSummary poll(Runnable heartbeat) {
        if (heartbeat == null) throw new IllegalArgumentException("An ingestion heartbeat is required");
        Runnable pulse = () -> {
            try { heartbeat.run(); }
            catch (RuntimeException leaseLost) { throw new SourceCollector.HeartbeatFailure(); }
        };
        if (!active()) return new PollSummary(false, 0, 0, 0, mapper.pendingCount(), List.of());
        pulse.run();
        syncSources();
        var outcomes = new ArrayList<SourcePoll>();
        for (SourceSpec spec : sources) {
            pulse.run();
            if (outcomes.size() >= properties.getMaxSourcesPerPoll()) break;
            if (!spec.enabled()) continue;
            SourceRegistration admitted = mapper.source(spec.id());
            if (admitted == null || !admitted.enabled() || !admitted.approved()) continue;
            var leased = transactions.execute(status -> {
                var state = mapper.lockSourceState(spec.id());
                long now = clock.millis();
                if (state == null || state.nextPollAt() > now || state.leaseUntil() > now) return null;
                return mapper.leaseSource(spec.id(), owner, now + properties.getSourceLeaseSeconds() * 1_000L, now) == 1 ? state : null;
            });
            if (leased == null) continue;
            try {
                if (mapper.pendingCount() >= properties.getMaxPendingItems()) throw new SourceCollector.CollectionFailure("INBOX_BACKPRESSURE");
                Instant discoveredAt = leased.snapshotCreatedAt() == null ? clock.instant() : Instant.ofEpochMilli(leased.snapshotCreatedAt());
                var snapshot = leased.snapshotPayload() == null
                        ? collector.open(spec, leased.etag(), leased.lastModified(), discoveredAt, pulse)
                        : new SourceCollector.Snapshot(leased.snapshotPayload(), leased.snapshotEtag(), leased.snapshotModified(), false);
                if (leased.snapshotPayload() == null && !snapshot.notModified()) {
                    Boolean saved = transactions.execute(status -> {
                        mapper.lockSourceState(spec.id());
                        SourceRegistration current = mapper.lockSource(spec.id());
                        if (current == null || !current.enabled() || !current.approved()) {
                            throw new SourceCollector.CollectionFailure("SOURCE_ADMISSION_CHANGED");
                        }
                        return mapper.saveSnapshot(spec.id(), owner, snapshot.payload(), snapshot.etag(), snapshot.lastModified(),
                                discoveredAt.toEpochMilli(), clock.millis()) == 1;
                    });
                    if (!Boolean.TRUE.equals(saved)) throw new SourceCollector.CollectionFailure("SOURCE_LEASE_LOST");
                }
                var batch = collector.collect(spec, policy(admitted), snapshot, leased.snapshotOffset(), discoveredAt, pulse);
                pulse.run();
                Integer inserted = transactions.execute(status -> {
                    var state = mapper.lockSourceState(spec.id());
                    if (state == null || !owner.equals(state.leaseOwner()) || state.leaseUntil() <= clock.millis()) {
                        throw new SourceCollector.CollectionFailure("SOURCE_LEASE_LOST");
                    }
                    SourceRegistration current = mapper.lockSource(spec.id());
                    if (current == null || !current.enabled() || !current.approved()) {
                        throw new SourceCollector.CollectionFailure("SOURCE_ADMISSION_CHANGED");
                    }
                    SourcePolicy currentPolicy = policy(current);
                    int stored = 0;
                    long backlog = mapper.pendingCount();
                    for (SourceMaterial material : batch.materials()) {
                        if (!currentPolicy.check(material).isEmpty()) throw new SourceCollector.CollectionFailure("SOURCE_ADMISSION_CHANGED");
                        String fingerprint = materialFingerprint(material);
                        String id = sha256(material.sourceId() + "\n" + material.eventKey() + "\n" + fingerprint);
                        if (mapper.inboxId(id) != null) continue;
                        if (!material.discoveryOnly() && ++backlog > properties.getMaxPendingItems()) {
                            throw new SourceCollector.CollectionFailure("INBOX_BACKPRESSURE");
                        }
                        String stateName = material.discoveryOnly() ? "DISCOVERY" : "PENDING";
                        mapper.insertInbox(new InboxRow(id, material.sourceId(), material.eventKey(), fingerprint,
                                json.writeValueAsString(material), stateName, material.discoveryOnly() ? "DISCOVERY_ONLY" : "",
                                0, clock.millis(), null, 0, clock.millis(), null));
                        stored++;
                    }
                    int changed;
                    if (batch.complete()) {
                        String cursor = batch.notModified() ? state.cursorValue() : batch.cursor();
                        changed = mapper.finishSource(spec.id(), owner, cursor, batch.etag(), batch.lastModified(), clock.millis(),
                                clock.millis() + properties.getSourcePollIntervalMs(), batch.materials().size());
                    } else {
                        // The frozen snapshot survives restarts. Final feed validators advance only after all bounded pages commit.
                        changed = mapper.advanceSnapshot(spec.id(), owner, batch.nextOffset(),
                                clock.millis() + properties.getPollDelayMs(), clock.millis(), batch.materials().size());
                    }
                    if (changed != 1) throw new SourceCollector.CollectionFailure("SOURCE_LEASE_LOST");
                    return stored;
                });
                outcomes.add(new SourcePoll(spec.id(), batch.notModified() ? "NOT_MODIFIED" : batch.complete() ? "COLLECTED" : "SNAPSHOT_PENDING", batch.materials().size(),
                        inserted == null ? 0 : inserted, null));
            } catch (SourceCollector.HeartbeatFailure leaseLost) {
                throw leaseLost;
            } catch (RuntimeException failure) {
                String code = failure instanceof SourceCollector.CollectionFailure known ? known.code() : "SOURCE_COLLECTION_FAILED";
                long next = clock.millis() + IngestionPolicy.backoff(leased.consecutiveFailures() + 1);
                transactions.executeWithoutResult(status -> mapper.failSource(spec.id(), owner, next, code));
                outcomes.add(new SourcePoll(spec.id(), "FAILED", 0, 0, code));
            }
            pulse.run();
        }
        return new PollSummary(true, outcomes.size(), outcomes.stream().mapToInt(SourcePoll::stored).sum(),
                (int) outcomes.stream().filter(result -> "FAILED".equals(result.state())).count(), mapper.pendingCount(), List.copyOf(outcomes));
    }

    /** Claims rows durably. An interrupted worker's rows become available after the inbox lease expires. */
    public List<InboxItem> pending(int limit) {
        if (!active()) return List.of();
        IngestionPolicy.requireLimit(limit);
        List<InboxItem> result = transactions.execute(status -> {
            var items = new ArrayList<InboxItem>();
            for (var row : mapper.lockDue(clock.millis(), limit)) {
                mapper.leaseInbox(row.id(), owner, clock.millis() + properties.getInboxLeaseSeconds() * 1_000L);
                try {
                    var material = json.readValue(row.materialPayload(), SourceMaterial.class);
                    IngestionPolicy.requireLiveMaterial(material);
                    items.add(new InboxItem(row.id(), material, row.attempts() + 1));
                } catch (RuntimeException corrupt) {
                    mapper.complete(row.id(), owner, "INVALID", "MATERIAL_PAYLOAD_INVALID", clock.millis());
                }
            }
            return List.copyOf(items);
        });
        return result == null ? List.of() : result;
    }

    public void complete(String id, String state, String detail) {
        IngestionPolicy.requireTerminal(state);
        String safeDetail = IngestionPolicy.safe(detail, 500, "PROCESSED");
        transactions.executeWithoutResult(status -> mapper.complete(id, owner, state, safeDetail, clock.millis()));
    }

    public void retry(String id, String code) {
        String safeCode = IngestionPolicy.safe(code, 80, "PROCESSING_FAILED");
        transactions.executeWithoutResult(status -> {
            var row = mapper.lockInbox(id);
            if (row != null && "PROCESSING".equals(row.state()) && owner.equals(row.leaseOwner())) {
                mapper.retry(id, owner, safeCode, clock.millis() + IngestionPolicy.backoff(row.attempts()));
            }
        });
    }

    public List<SourceStatus> status() {
        return mapper.sourceStates().stream().map(row -> new SourceStatus(row.sourceId(), row.cursorValue(),
                row.lastAttemptAt(), row.lastSuccessAt(), row.nextPollAt(), row.consecutiveFailures(), row.lastItemCount(), row.errorCode(),
                row.snapshotPayload() != null, row.snapshotOffset())).toList();
    }

    /** Protected operations callers may inspect results without receiving article bodies or network URLs. */
    public List<InboxStatus> inboxStatus() {
        return mapper.recentInbox().stream().map(row -> new InboxStatus(row.id(), row.sourceId(), row.eventKey(), row.state(),
                row.detail(), row.attempts(), row.createdAt(), row.completedAt(), row.nextAttemptAt())).toList();
    }

    /** Requeue held or known failed editorial work. Successful/uncertain model or publication work is never reset here. */
    public void requeue(String id) {
        if (!active()) throw new IllegalStateException("LIVE_EXTERNAL_ACCESS_DISABLED");
        transactions.executeWithoutResult(status -> {
            var row = mapper.lockInbox(id);
            IngestionPolicy.requireRequeueable(row);
            var source = mapper.lockSource(row.sourceId());
            var material = json.readValue(row.materialPayload(), SourceMaterial.class);
            if (source == null || !policy(source).check(material).isEmpty()) throw new IllegalStateException("SOURCE_NOT_ADMITTED");
            mapper.requeue(id, clock.millis());
        });
    }


    private static SourcePolicy policy(SourceRegistration source) { return source.policy(); }

    private String materialFingerprint(SourceMaterial material) {
        return sha256(json.writeValueAsString(material.versionIdentity(false, false)));
    }

}
