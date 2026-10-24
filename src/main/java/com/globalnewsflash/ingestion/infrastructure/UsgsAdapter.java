package com.globalnewsflash.ingestion.infrastructure;

import com.globalnewsflash.ingestion.application.SourceAdapter;
import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourceMaterial.Fact;
import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class UsgsAdapter implements SourceAdapter {
    private final JsonMapper json;

    public UsgsAdapter(JsonMapper json) {
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }

    @Override public String sourceId() { return "usgs"; }

    @Override
    public List<SourceMaterial> parse(String payload, Instant discoveredAt, boolean synthetic) {
        PayloadLimits.check(payload, discoveredAt);
        JsonNode root;
        try { root = json.readTree(payload); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid USGS JSON", exception); }
        if (root == null || !"FeatureCollection".equals(root.path("type").asText())
                || !root.path("features").isArray()) {
            throw new IllegalArgumentException("USGS FeatureCollection is required");
        }
        PayloadLimits.checkMode(root, synthetic);
        var features = root.path("features");
        if (features.size() > PayloadLimits.MAX_ITEMS) throw new IllegalArgumentException("USGS item limit exceeded");
        var result = new ArrayList<SourceMaterial>();
        for (JsonNode feature : features) {
            String id = text(feature, "id");
            if (id.length() > 235) {
                throw new IllegalArgumentException("USGS event ID exceeds the supported identity length");
            }
            JsonNode properties = feature.path("properties");
            if (!"earthquake".equals(text(properties, "type"))) {
                throw new IllegalArgumentException("Only earthquake records are supported by this adapter");
            }
            String producer = text(properties, "net");
            String url = CanonicalUrl.normalize(text(properties, "url"));
            if (!"earthquake.usgs.gov".equals(URI.create(url).getHost())) {
                throw new IllegalArgumentException("USGS event URLs must identify the official earthquake host");
            }
            Instant occurredAt = timestamp(properties, "time", true);
            Instant updatedAt = timestamp(properties, "updated", false);
            String place = nullableText(properties.path("place"));
            JsonNode magnitude = properties.path("mag");
            if (!magnitude.isMissingNode() && !magnitude.isNull() && !magnitude.isNumber()) {
                throw new IllegalArgumentException("USGS magnitude must be numeric or null");
            }
            List<Fact> facts = new ArrayList<>();
            if (magnitude.isNumber() && place != null) {
                String formattedMagnitude = magnitude.decimalValue().stripTrailingZeros().toPlainString();
                facts.add(new Fact("magnitude", "USGS reports an earthquake with magnitude "
                        + formattedMagnitude + "."));
                facts.add(new Fact("place", "USGS locates the earthquake at " + place + "."));
                facts.add(new Fact("occurredAt", "USGS records the earthquake occurrence time as "
                        + occurredAt + "."));
            }
            String title = nullableText(properties.path("title"));
            if (title == null) title = "USGS earthquake report " + id;
            result.add(new SourceMaterial(sourceId(), id, "usgs:" + id, url, title, "EARTHQUAKE", producer,
                    "usgs:" + id, occurredAt, null, updatedAt, discoveredAt, facts, false, synthetic));
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String name) {
        String value = nullableText(node.path(name));
        if (value == null) throw new IllegalArgumentException("USGS field " + name + " is required");
        return value;
    }

    private static String nullableText(JsonNode node) {
        return node.isString() && !node.asText().isBlank() ? node.asText().strip() : null;
    }

    private static Instant timestamp(JsonNode node, String name, boolean required) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) {
            if (required) throw new IllegalArgumentException("USGS field " + name + " is required");
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("USGS time field must be integer milliseconds");
        }
        return Instant.ofEpochMilli(value.longValue());
    }
}
