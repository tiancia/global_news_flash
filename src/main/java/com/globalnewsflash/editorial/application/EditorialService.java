package com.globalnewsflash.editorial.application;

import com.globalnewsflash.editorial.domain.EditorFailure;
import com.globalnewsflash.editorial.domain.EditorResponse;
import com.globalnewsflash.editorial.domain.EditorialDraft;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.editorial.domain.EditorialRules;
import com.globalnewsflash.editorial.port.in.EditorialUseCase;
import com.globalnewsflash.editorial.port.out.EditorPort;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Coordinates the evidence decision, one editor call, and response validation. */
@Service
public final class EditorialService implements EditorialUseCase {
    private final Clock clock;
    private final EditorPort editor;

    public EditorialService(Clock clock, EditorPort editor) { this.clock = clock; this.editor = editor; }

    public List<String> validateEvidence(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy) {
        return rules().validateEvidence(materials, sources, policy);
    }

    public EditorialResult evaluate(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy) {
        var rules = rules();
        var prepared = rules.prepare(materials, sources, policy);
        if (prepared.rejection() != null) return prepared.rejection();
        EditorResponse response;
        try { response = editor.edit(prepared.request()); }
        catch (EditorFailure safe) { throw safe; }
        catch (RuntimeException failure) { return rules.editorFailed(prepared, policy); }
        return rules.complete(prepared, materials, policy, response);
    }

    public List<String> validateStoredDraft(List<SourceMaterial> materials, List<SourcePolicy> sources,
            EditorialPolicy policy, EditorialDraft draft) {
        return rules().validateStoredDraft(materials, sources, policy, draft);
    }

    private EditorialRules rules() {
        return new EditorialRules(clock, editor.supports(true), editor.supports(false), editor.livePromptVersion());
    }
}
