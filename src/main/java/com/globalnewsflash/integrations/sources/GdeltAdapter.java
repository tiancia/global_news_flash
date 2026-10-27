package com.globalnewsflash.integrations.sources;

import com.globalnewsflash.ingestion.application.SourceAdapter;
import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class GdeltAdapter implements SourceAdapter {
    private final JsonMapper json;

    public GdeltAdapter(JsonMapper json) {
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }

    @Override public String sourceId() { return "gdelt"; }

    @Override
    public List<SourceMaterial> parse(String payload, Instant discoveredAt, boolean synthetic) {
        PayloadLimits.check(payload, discoveredAt);
        JsonNode root;
        try { root = json.readTree(payload); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid GDELT JSON", exception); }
        if (root == null || !root.path("articles").isArray()) {
            throw new IllegalArgumentException("GDELT articles array is required");
        }
        PayloadLimits.checkMode(root, synthetic);
        var articles = root.path("articles");
        if (articles.size() > PayloadLimits.MAX_ITEMS) throw new IllegalArgumentException("GDELT item limit exceeded");
        var result = new ArrayList<SourceMaterial>();
        for (JsonNode article : articles) {
            String url = CanonicalUrl.normalize(text(article, "url"));
            String headline = text(article, "title");
            String producer = URI.create(url).getHost();
            // seendate is intentionally not mapped to occurrence, publication, or source revision time.
            result.add(new SourceMaterial(sourceId(), url, StableEventKey.hashed(sourceId(), url), url, headline, "DISCOVERY", producer,
                    "gdelt:" + producer, null, null, null, discoveredAt, List.of(), true, synthetic));
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString() || value.asText().isBlank()) {
            throw new IllegalArgumentException("GDELT field " + name + " is required");
        }
        return value.asText().strip();
    }
}
