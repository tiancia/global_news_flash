package com.globalnewsflash.automation.port.in;

import com.globalnewsflash.automation.domain.DemoScenario;

/** Public use cases of the automation module. */
public interface DemoUseCase {
    record DemoResult(String mode, DemoScenario scenario, PipelineUseCase.PipelineRun pipeline,
                             String deliveryState, String messageId) {}
    DemoResult run(DemoScenario scenario);
}
