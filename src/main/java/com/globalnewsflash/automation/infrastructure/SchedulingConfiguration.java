package com.globalnewsflash.automation.infrastructure;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration {
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // Collection may take minutes; alert, digest and publication tasks need independent capacity.
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("news-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setErrorHandler(failure -> LoggerFactory.getLogger(SchedulingConfiguration.class)
                .warn("SCHEDULED_TASK_FAILED"));
        return scheduler;
    }
}
