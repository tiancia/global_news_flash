package com.globalnewsflash.automation.port.in;

import java.util.List;
import java.util.Map;

/** Public use cases of the automation module. */
public interface AutomationUseCase {

    List<String> readinessReasons();
    Map<String, Object> status();
    Map<String, Object> runOnce();
    Object poll();
    Object ingestionStatus();
    Object inboxStatus();
    Object requeue(String id);
    Object articleFailures();
    Object jobs();
    Object alerts();
}
