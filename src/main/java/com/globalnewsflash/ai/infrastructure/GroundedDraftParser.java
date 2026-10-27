package com.globalnewsflash.ai.infrastructure;

import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;
import java.util.ArrayList;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The grounded draft contract shared by both live providers, after envelope and usage validation. */
final class GroundedDraftParser {
    private final JsonMapper json;

    GroundedDraftParser(JsonMapper json) {
        this.json = json;
    }

    EditorResponse parse(String payload, String provider, String model, EditorRequest request, EditorResponse.Usage usage) {
        if (payload == null || payload.isBlank() || payload.length() > 65_536) throw invalid();
        JsonNode draft = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(payload);
        requireFields(draft, Set.of("risk", "relevant", "statements"));
        String risk = text(draft, "risk", 16);
        if (!Set.of("LOW", "REVIEW", "BLOCKED").contains(risk) || !draft.path("relevant").isBoolean()
                || !draft.path("statements").isArray() || draft.path("statements").size() > 5) {
            throw invalid();
        }
        var statements = new ArrayList<EditorResponse.Statement>();
        for (JsonNode statement : draft.path("statements")) {
            requireFields(statement, Set.of("sourceId", "materialId", "factId", "text", "quote"));
            statements.add(new EditorResponse.Statement(text(statement, "sourceId", 80),
                    text(statement, "materialId", 2_048), text(statement, "factId", 240),
                    text(statement, "text", 600), text(statement, "quote", 1_600)));
        }
        return new EditorResponse(statements, provider, model, request.promptVersion(), request.contractVersion(),
                usage, false, risk, draft.path("relevant").booleanValue());
    }

    private static void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        for (String field : fields) if (!node.has(field) || node.path(field).isNull()) throw invalid();
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asText().isBlank() || value.asText().length() > maxLength) throw invalid();
        return value.asText().strip();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid structured editorial response");
    }
}
