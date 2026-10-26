package com.globalnewsflash.ingestion.application;

import com.globalnewsflash.automation.infrastructure.PipelineMapper.SourceRow;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.infrastructure.LiveIngestionMapper;
import com.globalnewsflash.ingestion.infrastructure.LiveIngestionProperties;
import com.globalnewsflash.ingestion.infrastructure.LiveSourceCollector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Durable live collection. Network I/O is outside database transactions; cursor commits with the whole inbox batch. */
@Service
public class LiveIngestionService {
    public record InboxItem(String id, SourceMaterial material, int attempts) {}
    public record SourcePoll(String sourceId, String state, int received, int stored, String code) {}
    public record PollSummary(boolean active, int polled, int stored, int failed, long pending, List<SourcePoll> sources) {}
    public record SourceStatus(String sourceId, String cursor, Long lastAttemptAt, Long lastSuccessAt,
            long nextPollAt, int consecutiveFailures, int lastItemCount, String errorCode,
            boolean snapshotPending, int snapshotOffset) {}
    public record InboxStatus(String id, String sourceId, String eventKey, String state, String detail, int attempts,
            long createdAt, Long completedAt, long nextAttemptAt) {}
    public record SourceSpec(String id, String type, String url, boolean enabled, boolean approved, String license,
            String producer, Set<String> permittedProducers, Set<String> allowedHosts, String articleSelector,
            String topic, boolean fetchArticle) {}

    private final LiveIngestionProperties properties;
    private final RuntimeProperties runtime;
    private final LiveIngestionMapper mapper;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final LiveSourceCollector collector;
    private final List<SourceSpec> sources;
    private final String owner = UUID.randomUUID().toString();

    public LiveIngestionService(LiveIngestionProperties properties, RuntimeProperties runtime,
            LiveIngestionMapper mapper, JsonMapper json, Clock clock, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.runtime = runtime;
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        if (properties.isEnabled() && "LIVE".equals(runtime.getMode()) && runtime.isExternalEnabled()) {
            properties.validate();
            sources = configuredSources(properties, json);
            collector = new LiveSourceCollector(properties, json);
        } else {
            // Offline/demo startup never needs complete live URLs, license declarations, or live-only transport settings.
            sources = List.of();
            collector = null;
        }
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
                SourceRow previous = mapper.lockSource(spec.id());
                boolean enabled = spec.enabled() && (!state.registered() || previous == null || previous.enabled());
                var row = new SourceRow(spec.id() + ":real", spec.id(), enabled, spec.approved(), false,
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
            catch (RuntimeException leaseLost) { throw new LiveSourceCollector.HeartbeatFailure(); }
        };
        if (!active()) return new PollSummary(false, 0, 0, 0, mapper.pendingCount(), List.of());
        pulse.run();
        syncSources();
        var outcomes = new ArrayList<SourcePoll>();
        for (SourceSpec spec : sources) {
            pulse.run();
            if (outcomes.size() >= properties.getMaxSourcesPerPoll()) break;
            if (!spec.enabled()) continue;
            SourceRow admitted = mapper.source(spec.id());
            if (admitted == null || !admitted.enabled() || !admitted.approved()) continue;
            var leased = transactions.execute(status -> {
                var state = mapper.lockSourceState(spec.id());
                long now = clock.millis();
                if (state == null || state.nextPollAt() > now || state.leaseUntil() > now) return null;
                return mapper.leaseSource(spec.id(), owner, now + properties.getSourceLeaseSeconds() * 1_000L, now) == 1 ? state : null;
            });
            if (leased == null) continue;
            try {
                if (mapper.pendingCount() >= properties.getMaxPendingItems()) throw new LiveSourceCollector.CollectionFailure("INBOX_BACKPRESSURE");
                Instant discoveredAt = leased.snapshotCreatedAt() == null ? clock.instant() : Instant.ofEpochMilli(leased.snapshotCreatedAt());
                var snapshot = leased.snapshotPayload() == null
                        ? collector.open(spec, leased.etag(), leased.lastModified(), discoveredAt, pulse)
                        : new LiveSourceCollector.Snapshot(leased.snapshotPayload(), leased.snapshotEtag(), leased.snapshotModified(), false);
                if (leased.snapshotPayload() == null && !snapshot.notModified()) {
                    Boolean saved = transactions.execute(status -> {
                        mapper.lockSourceState(spec.id());
                        SourceRow current = mapper.lockSource(spec.id());
                        if (current == null || !current.enabled() || !current.approved()) {
                            throw new LiveSourceCollector.CollectionFailure("SOURCE_ADMISSION_CHANGED");
                        }
                        return mapper.saveSnapshot(spec.id(), owner, snapshot.payload(), snapshot.etag(), snapshot.lastModified(),
                                discoveredAt.toEpochMilli(), clock.millis()) == 1;
                    });
                    if (!Boolean.TRUE.equals(saved)) throw new LiveSourceCollector.CollectionFailure("SOURCE_LEASE_LOST");
                }
                var batch = collector.collect(spec, policy(admitted), snapshot, leased.snapshotOffset(), discoveredAt, pulse);
                pulse.run();
                Integer inserted = transactions.execute(status -> {
                    var state = mapper.lockSourceState(spec.id());
                    if (state == null || !owner.equals(state.leaseOwner()) || state.leaseUntil() <= clock.millis()) {
                        throw new LiveSourceCollector.CollectionFailure("SOURCE_LEASE_LOST");
                    }
                    SourceRow current = mapper.lockSource(spec.id());
                    if (current == null || !current.enabled() || !current.approved()) {
                        throw new LiveSourceCollector.CollectionFailure("SOURCE_ADMISSION_CHANGED");
                    }
                    SourcePolicy currentPolicy = policy(current);
                    int stored = 0;
                    long backlog = mapper.pendingCount();
                    for (SourceMaterial material : batch.materials()) {
                        if (!currentPolicy.check(material).isEmpty()) throw new LiveSourceCollector.CollectionFailure("SOURCE_ADMISSION_CHANGED");
                        String fingerprint = materialFingerprint(material);
                        String id = hash(material.sourceId() + "\n" + material.eventKey() + "\n" + fingerprint);
                        if (mapper.inboxId(id) != null) continue;
                        if (!material.discoveryOnly() && ++backlog > properties.getMaxPendingItems()) {
                            throw new LiveSourceCollector.CollectionFailure("INBOX_BACKPRESSURE");
                        }
                        String stateName = material.discoveryOnly() ? "DISCOVERY" : "PENDING";
                        mapper.insertInbox(new LiveIngestionMapper.InboxRow(id, material.sourceId(), material.eventKey(), fingerprint,
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
                    if (changed != 1) throw new LiveSourceCollector.CollectionFailure("SOURCE_LEASE_LOST");
                    return stored;
                });
                outcomes.add(new SourcePoll(spec.id(), batch.notModified() ? "NOT_MODIFIED" : batch.complete() ? "COLLECTED" : "SNAPSHOT_PENDING", batch.materials().size(),
                        inserted == null ? 0 : inserted, null));
            } catch (LiveSourceCollector.HeartbeatFailure leaseLost) {
                throw leaseLost;
            } catch (RuntimeException failure) {
                String code = failure instanceof LiveSourceCollector.CollectionFailure known ? known.code() : "SOURCE_COLLECTION_FAILED";
                long next = clock.millis() + backoff(leased.consecutiveFailures() + 1);
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
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Inbox limit must be between 1 and 100");
        List<InboxItem> result = transactions.execute(status -> {
            var items = new ArrayList<InboxItem>();
            for (var row : mapper.lockDue(clock.millis(), limit)) {
                mapper.leaseInbox(row.id(), owner, clock.millis() + properties.getInboxLeaseSeconds() * 1_000L);
                try {
                    var material = json.readValue(row.materialPayload(), SourceMaterial.class);
                    if (material.synthetic() || material.discoveryOnly()) throw new IllegalArgumentException("Invalid live inbox material");
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
        if (state == null || !state.matches("[A-Z][A-Z0-9_]{0,39}") || Set.of("PENDING", "RETRY", "PROCESSING").contains(state)) {
            throw new IllegalArgumentException("A terminal inbox result is required");
        }
        String safeDetail = safe(detail, 500, "PROCESSED");
        transactions.executeWithoutResult(status -> mapper.complete(id, owner, state, safeDetail, clock.millis()));
    }

    public void retry(String id, String code) {
        String safeCode = safe(code, 80, "PROCESSING_FAILED");
        transactions.executeWithoutResult(status -> {
            var row = mapper.lockInbox(id);
            if (row != null && "PROCESSING".equals(row.state()) && owner.equals(row.leaseOwner())) {
                mapper.retry(id, owner, safeCode, clock.millis() + backoff(row.attempts()));
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
            if (row == null) throw new IllegalArgumentException("Unknown inbox item");
            if (!Set.of("HELD", "FAILED", "EXPIRED", "REJECTED", "RETRY", "RETRY_WAIT", "RETRYABLE").contains(row.state())) {
                throw new IllegalStateException("INBOX_RESULT_NOT_REQUEUEABLE");
            }
            var source = mapper.lockSource(row.sourceId());
            var material = json.readValue(row.materialPayload(), SourceMaterial.class);
            if (source == null || !policy(source).check(material).isEmpty()) throw new IllegalStateException("SOURCE_NOT_ADMITTED");
            mapper.requeue(id, clock.millis());
        });
    }

    private static String safe(String text, int max, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        if (text.length() > max || !text.matches("[A-Za-z0-9_:.=,; /-]+")) return fallback;
        return text;
    }

    private static long backoff(int attempts) {
        return Math.min(3_600_000L, 30_000L * (1L << Math.min(7, Math.max(0, attempts - 1))));
    }

    private static SourcePolicy policy(SourceRow row) {
        return new SourcePolicy(row.sourceId(), row.enabled(), row.approved(), false, row.licenseVersion(),
                row.permittedProducers().isBlank() ? Set.of() : Set.copyOf(Arrays.asList(row.permittedProducers().split(","))));
    }

    private String materialFingerprint(SourceMaterial material) {
        // Rediscovery is not a new version; source revision timestamps and changed source text are.
        return hash(json.writeValueAsString(Arrays.asList(material.sourceId(), material.externalId(), material.eventKey(),
                material.canonicalUrl(), material.headline(), material.topic(), material.producer(), material.evidenceChainId(),
                material.occurredAt(), material.publishedAt(), material.sourceUpdatedAt(), material.facts(), material.discoveryOnly())));
    }

    private static List<SourceSpec> configuredSources(LiveIngestionProperties properties, JsonMapper json) {
        var result = new ArrayList<SourceSpec>();
        var usgs = properties.getUsgs();
        var fed = properties.getFed();
        var gdelt = properties.getGdelt();
        if (usgs == null || fed == null || gdelt == null) throw invalidConfiguration();
        result.add(new SourceSpec("usgs", "USGS", urlOr(usgs.getUrl(), "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson"),
                usgs.isEnabled(), usgs.isApproved(), license(usgs.getLicenseVersion()), null, producers(usgs.getPermittedProducers()),
                Set.of("earthquake.usgs.gov"), "", "EARTHQUAKE", false));
        result.add(new SourceSpec("fed", "FED", urlOr(fed.getUrl(), "https://www.federalreserve.gov/feeds/press_all.xml"),
                fed.isEnabled(), fed.isApproved(), license(fed.getLicenseVersion()), "federal-reserve-board", producers(fed.getPermittedProducers()),
                Set.of("www.federalreserve.gov", "federalreserve.gov"),
                fed.getArticleSelector() == null || fed.getArticleSelector().isBlank() ? "#article" : fed.getArticleSelector(),
                "OFFICIAL_ANNOUNCEMENT", true));
        result.add(new SourceSpec("gdelt", "GDELT", urlOr(gdelt.getUrl(), "https://api.gdeltproject.org/api/v2/doc/doc"),
                gdelt.isEnabled(), gdelt.isApproved(), license(gdelt.getLicenseVersion()), null, producers(gdelt.getPermittedProducers()),
                Set.of("api.gdeltproject.org"), "", "DISCOVERY", false));
        try {
            var configured = json.readValue(properties.getRssFeedsJson(), LiveIngestionProperties.FeedDefinition[].class);
            if (configured == null || configured.length > 27) throw invalidConfiguration();
            for (var feed : configured) {
                if (feed == null || feed.id() == null || Set.of("usgs", "fed", "gdelt").contains(feed.id())
                        || feed.producer() == null || feed.producer().isBlank() || feed.producer().length() > 200
                        || feed.allowedHosts() == null || feed.allowedHosts().isEmpty()) throw invalidConfiguration();
                Set<String> hosts = new LinkedHashSet<>();
                for (String host : feed.allowedHosts()) {
                    if (host == null || host.contains("/") || host.contains(":")) throw invalidConfiguration();
                    hosts.add(java.net.URI.create(CanonicalUrl.normalize("https://" + host + "/")).getHost());
                }
                String selector = feed.articleSelector() == null || feed.articleSelector().isBlank() ? "article, main" : feed.articleSelector();
                result.add(new SourceSpec(feed.id(), "RSS", CanonicalUrl.normalize(feed.url()), feed.enabled(), feed.approved(),
                        license(feed.licenseVersion()), feed.producer(), producers(feed.permittedProducers()), Set.copyOf(hosts),
                        selector, feed.topic() == null || feed.topic().isBlank() ? "WORLD" : feed.topic(), feed.fetchArticle()));
            }
        } catch (RuntimeException invalid) { throw invalidConfiguration(); }
        var ids = new LinkedHashSet<String>();
        for (var spec : result) {
            if (spec.id() == null || !spec.id().matches("[a-z][a-z0-9-]{0,39}") || !ids.add(spec.id())
                    || !spec.allowedHosts().contains(java.net.URI.create(spec.url()).getHost())
                    || spec.articleSelector().length() > 500 || !spec.topic().matches("[A-Z][A-Z0-9_]{0,79}")) throw invalidConfiguration();
            if (spec.approved() && (spec.permittedProducers().isEmpty() || "pending".equalsIgnoreCase(spec.license()))) throw invalidConfiguration();
            if (spec.approved() && spec.producer() != null && !spec.permittedProducers().contains(spec.producer())) throw invalidConfiguration();
        }
        return List.copyOf(result);
    }

    private static String license(String license) {
        if (license == null || license.isBlank() || license.length() > 120) throw invalidConfiguration();
        return license.strip();
    }

    private static Set<String> producers(List<String> producers) {
        if (producers == null) throw invalidConfiguration();
        var result = new LinkedHashSet<String>();
        for (String producer : producers) {
            if (producer == null || producer.isBlank() || producer.contains(",") || producer.length() > 200) throw invalidConfiguration();
            result.add(producer.strip());
        }
        if (String.join(",", result).length() > 1000) throw invalidConfiguration();
        return java.util.Collections.unmodifiableSet(result);
    }

    private static String urlOr(String configured, String fallback) {
        return CanonicalUrl.normalize(configured == null || configured.isBlank() ? fallback : configured);
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Invalid explicitly approved live source configuration");
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
