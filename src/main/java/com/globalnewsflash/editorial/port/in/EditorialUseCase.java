package com.globalnewsflash.editorial.port.in;

import com.globalnewsflash.editorial.domain.EditorialDraft;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.editorial.domain.EditorialResult;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import java.util.List;

/** Public use cases of the editorial module. */
public interface EditorialUseCase {

    List<String> validateEvidence(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy);
    EditorialResult evaluate(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy);
    List<String> validateStoredDraft(List<SourceMaterial> materials, List<SourcePolicy> sources, EditorialPolicy policy, EditorialDraft draft);
}
