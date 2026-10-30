package com.globalnewsflash.automation.port.in;

import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import java.util.List;

/** Public use cases of the automation module. */
public interface LivePipelineUseCase {

    PipelineUseCase.PipelineRun process(List<SourceMaterial> materials, EditorialPolicy policy, PipelineUseCase.BudgetAllocation allocation);
    int recoverExpired();
    Object resolveCost(String id, long actualMicros, String note);
    PipelineUseCase.PipelineRun retryJob(String id);
    void requestRetry(SourceMaterial material);
}
