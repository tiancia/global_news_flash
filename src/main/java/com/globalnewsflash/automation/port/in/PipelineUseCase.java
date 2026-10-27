package com.globalnewsflash.automation.port.in;

import com.globalnewsflash.automation.domain.EventVersion;
import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceRegistration;
import java.util.List;

/** Public use cases of the automation module. */
public interface PipelineUseCase {
    record PipelineRun(String eventKey, int eventVersion, String state, List<String> reasons,
                              String publicationId, boolean duplicate, boolean synthetic) {}
    record BudgetAllocation(String periodId, long estimatedMicros) {}
    PipelineRun process(List<SourceMaterial> input, EditorialPolicy policy, BudgetAllocation budget);
    List<SourceRegistration> sources();
    List<EventVersion> versions();
    long materialCount();
    long auditCount();
    void setSourceEnabled(String id, boolean enabled);
    List<SourcePolicy> sourcePolicies(boolean synthetic);
}
