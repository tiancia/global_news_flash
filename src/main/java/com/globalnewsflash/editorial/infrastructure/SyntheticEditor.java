package com.globalnewsflash.editorial.infrastructure;

import com.globalnewsflash.editorial.application.EditorPort;
import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;
import org.springframework.stereotype.Component;

/** Deterministic fixture editor. It makes no claims about real model quality or semantic verification. */
@Component
public final class SyntheticEditor implements EditorPort {
    @Override
    public EditorResponse edit(EditorRequest request) {
        if (!request.synthetic() || request.facts().isEmpty()) {
            throw new IllegalArgumentException("Synthetic editor requires synthetic evidence");
        }
        var statements = request.facts().stream()
                .map(fact -> new EditorResponse.Statement(fact.sourceId(), fact.materialId(),
                        fact.factId(), fact.text()))
                .toList();
        return new EditorResponse(statements, "synthetic", "exact-evidence-v1",
                request.promptVersion(), request.contractVersion(),
                new EditorResponse.Usage(0, 0, true), true);
    }
}
