package com.globalnewsflash.editorial.domain;

import java.util.List;

public record EditorialDraft(String requestId, List<EditorResponse.Statement> statements, String body,
        String provider, String model, String promptVersion, String contractVersion,
        EditorResponse.Usage usage, boolean synthetic) {
    public EditorialDraft {
        statements = List.copyOf(statements);
    }
}
