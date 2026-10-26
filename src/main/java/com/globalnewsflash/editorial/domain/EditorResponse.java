package com.globalnewsflash.editorial.domain;

import java.util.List;

/** The provider cannot supply free-form body text outside referenced factual statements. */
public record EditorResponse(List<Statement> statements, String provider, String model,
        String promptVersion, String contractVersion, Usage usage, boolean synthetic,
        String risk, boolean relevant) {
    public EditorResponse {
        statements = statements == null ? List.of() : List.copyOf(statements);
    }

    public EditorResponse(List<Statement> statements, String provider, String model,
            String promptVersion, String contractVersion, Usage usage, boolean synthetic) {
        this(statements, provider, model, promptVersion, contractVersion, usage, synthetic,
                synthetic ? "LOW" : "UNASSESSED", synthetic);
    }

    public record Statement(String sourceId, String materialId, String factId, String text, String quote) {
        public Statement(String sourceId, String materialId, String factId, String text) {
            this(sourceId, materialId, factId, text, null);
        }
    }

    public record Usage(long inputTokens, long outputTokens, boolean estimated) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("Token usage cannot be negative");
            }
        }
    }
}
