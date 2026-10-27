package com.globalnewsflash.ingestion.infrastructure;

import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import com.globalnewsflash.ingestion.domain.SourceSpec;
import com.globalnewsflash.ingestion.port.out.SourceCatalog;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Decodes YAML/JSON inputs; durable source enablement remains owned by the ingestion use case. */
@Component
public final class ConfiguredSourceCatalog implements SourceCatalog {
    private final LiveIngestionProperties properties;
    private final RuntimeSettings runtime;
    private final JsonMapper json;

    public ConfiguredSourceCatalog(LiveIngestionProperties properties, RuntimeSettings runtime, JsonMapper json) {
        this.properties = properties;
        this.runtime = runtime;
        this.json = json;
    }

    public List<SourceSpec> sources() {
        if (!properties.isEnabled() || !runtime.live()) return List.of();
        properties.validate();
        return configuredSources(properties, json);
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

}
