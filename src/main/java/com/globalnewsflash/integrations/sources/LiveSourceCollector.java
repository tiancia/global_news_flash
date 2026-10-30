package com.globalnewsflash.integrations.sources;

import static com.globalnewsflash.shared.Hashing.sha256;

import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourceMaterial.Fact;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceSpec;
import com.globalnewsflash.ingestion.port.out.IngestionSettings;
import com.globalnewsflash.ingestion.port.out.SourceCollector;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Network collector has no database responsibility; its complete batch is committed by IngestionUseCase. */
public final class LiveSourceCollector implements SourceCollector {
    private final IngestionSettings properties;
    private final BoundedHttpsFetcher fetcher;
    private final ArticleTextExtractor articles;
    private final UsgsAdapter usgs;
    private final FederalReserveRssAdapter fed = new FederalReserveRssAdapter();
    private final GdeltAdapter gdelt;
    private final SyndicationFeedParser syndication = new SyndicationFeedParser();

    public LiveSourceCollector(IngestionSettings properties, JsonMapper json) {
        this(properties, json, new BoundedHttpsFetcher(properties));
    }

    LiveSourceCollector(IngestionSettings properties, JsonMapper json, BoundedHttpsFetcher fetcher) {
        this.properties = properties;
        this.fetcher = fetcher;
        articles = new ArticleTextExtractor(properties);
        usgs = new UsgsAdapter(json);
        gdelt = new GdeltAdapter(json);
    }

    public Snapshot open(SourceSpec source, String etag, String lastModified, Instant discoveredAt, Runnable heartbeat) {
        try {
            var feed = fetcher.fetch(source.url(), source.allowedHosts(), etag, lastModified, heartbeat);
            if (feed.status() != 304) metadata(source, feed.body(), discoveredAt); // Validate before retaining a durable snapshot.
            return new Snapshot(feed.body(), feed.etag(), feed.lastModified(), feed.status() == 304);
        } catch (HeartbeatFailure failure) {
            throw failure;
        } catch (BoundedHttpsFetcher.FetchFailure known) {
            throw new CollectionFailure(known.code());
        } catch (CollectionFailure known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw new CollectionFailure("SOURCE_PARSE_FAILED");
        }
    }

    public Batch collect(SourceSpec source, SourcePolicy policy, Snapshot snapshot, int offset, Instant discoveredAt, Runnable heartbeat) {
        if (snapshot.notModified()) return new Batch(List.of(), snapshot.etag(), snapshot.lastModified(), null, true, 0, true);
        try {
            List<SourceMaterial> metadata = metadata(source, snapshot.payload(), discoveredAt);
            if (offset < 0 || offset > metadata.size()) throw new CollectionFailure("INVALID_SNAPSHOT_OFFSET");
            int nextOffset = Math.min(metadata.size(), offset + properties.getMaxItemsPerSource());
            List<SourceMaterial> selected = metadata.subList(offset, nextOffset).stream()
                    .filter(item -> !snapshot.excludedItems().contains(sha256(item.externalId()))).toList();
            var failures = new ArrayList<ItemFailure>();
            List<SourceMaterial> materials = switch (source.type()) {
                case "USGS", "GDELT" -> admitted(selected, policy);
                case "FED" -> officialAnnouncements(source, policy, selected, discoveredAt, heartbeat, failures);
                case "RSS" -> syndicated(source, policy, snapshot.payload(), selected, discoveredAt, heartbeat, failures);
                default -> throw new CollectionFailure("UNKNOWN_SOURCE_TYPE");
            };
            String cursor = metadata.stream().map(LiveSourceCollector::materialTime).max(Comparator.naturalOrder())
                    .filter(time -> !Instant.EPOCH.equals(time)).map(Instant::toString).orElse("feed:" + sha256(snapshot.payload()));
            return new Batch(List.copyOf(materials), snapshot.etag(), snapshot.lastModified(), cursor, false,
                    nextOffset, nextOffset == metadata.size(), List.copyOf(failures));
        } catch (HeartbeatFailure failure) { throw failure; }
        catch (BoundedHttpsFetcher.FetchFailure known) { throw new CollectionFailure(known.code()); }
        catch (CollectionFailure known) { throw known; }
        catch (RuntimeException invalid) { throw new CollectionFailure("SOURCE_PARSE_FAILED"); }
    }

    private List<SourceMaterial> metadata(SourceSpec source, String payload, Instant discoveredAt) {
        List<SourceMaterial> parsed = switch (source.type()) {
            case "USGS" -> usgs.parse(payload, discoveredAt, false);
            case "FED" -> fed.parse(payload, discoveredAt, false);
            case "GDELT" -> gdelt.parse(payload, discoveredAt, false);
            case "RSS" -> syndication.parse(payload, source.url()).stream().map(entry -> new SourceMaterial(
                    source.id(), entry.externalId(), source.id() + ":" + sha256(entry.externalId()), entry.url(), entry.title(),
                    source.topic(), source.producer(), source.id() + ":" + sha256(entry.url()), null, entry.publishedAt(),
                    entry.updatedAt(), discoveredAt, List.of(), true, false)).toList();
            default -> throw new CollectionFailure("UNKNOWN_SOURCE_TYPE");
        };
        return parsed.stream().sorted(Comparator.comparing(LiveSourceCollector::materialTime).reversed()
                .thenComparing(SourceMaterial::externalId)).toList();
    }

    private List<SourceMaterial> admitted(List<SourceMaterial> candidates, SourcePolicy policy) {
        return candidates.stream().filter(material -> policy.check(material).isEmpty())
                .toList();
    }

    private List<SourceMaterial> officialAnnouncements(SourceSpec source, SourcePolicy policy,
            List<SourceMaterial> selected, Instant discoveredAt, Runnable heartbeat, List<ItemFailure> failures) {
        var result = new ArrayList<SourceMaterial>();
        for (SourceMaterial metadata : admitted(selected, policy)) {
            collectItem(metadata.externalId(), failures, () -> {
                var page = fetcher.fetch(metadata.canonicalUrl(), source.allowedHosts(), null, null, heartbeat);
                List<Fact> facts = articles.article(page.body(), source.articleSelector());
                if (facts.isEmpty()) throw new CollectionFailure("ARTICLE_BODY_INSUFFICIENT");
                result.add(new SourceMaterial(metadata.sourceId(), metadata.externalId(), metadata.eventKey(),
                        metadata.canonicalUrl(), metadata.headline(), metadata.topic(), metadata.producer(), metadata.evidenceChainId(),
                        metadata.occurredAt(), metadata.publishedAt(), modified(page.lastModified()), discoveredAt, facts, false, false));
            });
        }
        return List.copyOf(result);
    }

    private List<SourceMaterial> syndicated(SourceSpec source, SourcePolicy policy, String payload,
            List<SourceMaterial> selected, Instant discoveredAt, Runnable heartbeat, List<ItemFailure> failures) {
        Set<String> ids = selected.stream().map(SourceMaterial::externalId).collect(java.util.stream.Collectors.toSet());
        var entries = syndication.parse(payload, source.url()).stream().filter(entry -> ids.contains(entry.externalId())).toList();
        var result = new ArrayList<SourceMaterial>();
        for (var entry : entries) {
            collectItem(entry.externalId(), failures, () -> {
                String url = CanonicalUrl.normalize(entry.url());
                if (!source.allowedHosts().contains(URI.create(url).getHost())) {
                    // Even content:encoded cannot grant evidence permissions for an unapproved article host.
                    return;
                }
                List<Fact> facts = entry.body() == null ? List.of() : articles.inline(entry.body());
                Instant revision = entry.updatedAt();
                if (facts.isEmpty() && source.fetchArticle()) {
                    var page = fetcher.fetch(url, source.allowedHosts(), null, null, heartbeat);
                    facts = articles.article(page.body(), source.articleSelector());
                    if (facts.isEmpty()) throw new CollectionFailure("ARTICLE_BODY_INSUFFICIENT");
                    if (revision == null) revision = modified(page.lastModified());
                }
                String eventKey = source.id() + ":" + sha256(entry.externalId());
                var material = new SourceMaterial(source.id(), entry.externalId(), eventKey, url, entry.title(),
                        source.topic(), source.producer(), source.id() + ":" + sha256(url), null, entry.publishedAt(),
                        revision, discoveredAt, facts, facts.isEmpty(), false);
                if (policy.check(material).isEmpty()) result.add(material);
            });
        }
        return List.copyOf(result);
    }

    private void collectItem(String externalId, List<ItemFailure> failures, Runnable collect) {
        try { collect.run(); }
        catch (HeartbeatFailure lost) { throw lost; }
        catch (BoundedHttpsFetcher.FetchFailure failure) { failures.add(new ItemFailure(sha256(externalId), failure.code())); }
        catch (CollectionFailure failure) { failures.add(new ItemFailure(sha256(externalId), failure.code())); }
        catch (RuntimeException invalid) { failures.add(new ItemFailure(sha256(externalId), "ARTICLE_PROCESSING_FAILED")); }
    }

    private static Instant materialTime(SourceMaterial material) {
        if (material.publishedAt() != null) return material.publishedAt();
        if (material.occurredAt() != null) return material.occurredAt();
        if (material.sourceUpdatedAt() != null) return material.sourceUpdatedAt();
        return Instant.EPOCH;
    }

    private static Instant modified(String header) {
        if (header == null) return null;
        try { return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
        catch (DateTimeParseException invalid) { return null; }
    }

}
