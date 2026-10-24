package com.globalnewsflash.editorial.infrastructure;

import com.globalnewsflash.editorial.domain.EditorResponse;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict local contract parser, usable by a future provider adapter after transport validation. */
@Component
public final class StructuredDraftParser {
    private static final int MAX_RESPONSE_CHARACTERS = 65_536;
    private final JsonMapper mapper;

    public StructuredDraftParser(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public EditorResponse parse(String payload) {
        if (payload == null || payload.isBlank() || payload.length() > MAX_RESPONSE_CHARACTERS) {
            throw invalid();
        }
        try {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(payload);
            requireFields(root, Set.of("statements", "provider", "model", "promptVersion",
                    "contractVersion", "usage", "synthetic"));
            if (!root.path("statements").isArray() || root.path("statements").isEmpty()
                    || root.path("statements").size() > 100 || !root.path("synthetic").isBoolean()) {
                throw invalid();
            }
            var statements = new ArrayList<EditorResponse.Statement>();
            for (JsonNode statement : root.path("statements")) {
                requireFields(statement, Set.of("sourceId", "materialId", "factId", "text"));
                statements.add(new EditorResponse.Statement(text(statement, "sourceId"),
                        text(statement, "materialId"), text(statement, "factId"), text(statement, "text")));
            }
            JsonNode usage = root.path("usage");
            requireFields(usage, Set.of("inputTokens", "outputTokens", "estimated"));
            if (!usage.path("inputTokens").isIntegralNumber() || !usage.path("inputTokens").canConvertToLong()
                    || !usage.path("outputTokens").isIntegralNumber() || !usage.path("outputTokens").canConvertToLong()
                    || !usage.path("estimated").isBoolean()) {
                throw invalid();
            }
            return new EditorResponse(statements, text(root, "provider"), text(root, "model"),
                    text(root, "promptVersion"), text(root, "contractVersion"),
                    new EditorResponse.Usage(usage.path("inputTokens").longValue(),
                            usage.path("outputTokens").longValue(), usage.path("estimated").booleanValue()),
                    root.path("synthetic").booleanValue());
        } catch (RuntimeException failure) {
            // Do not include external payloads or provider error details in exceptions.
            throw invalid();
        }
    }

    private static void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) {
            throw invalid();
        }
        for (String field : fields) {
            if (!node.has(field) || node.path(field).isNull()) {
                throw invalid();
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.stringValue().isBlank()) {
            throw invalid();
        }
        return value.stringValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid structured editorial response");
    }
}
