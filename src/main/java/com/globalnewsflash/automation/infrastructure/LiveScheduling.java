package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.automation.port.in.AutomationUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "news.automation.enabled", havingValue = "true")
public class LiveScheduling {
    private final AutomationUseCase automation;
    public LiveScheduling(AutomationUseCase automation) { this.automation = automation; }

    @Scheduled(fixedDelayString = "${news.automation.delay-ms:60000}",
               initialDelayString = "${news.automation.initial-delay-ms:10000}")
    public void tick() { automation.runOnce(); }
}
