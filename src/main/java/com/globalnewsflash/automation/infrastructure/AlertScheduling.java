package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.automation.port.in.NotificationUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "news.alert.enabled", havingValue = "true")
public class AlertScheduling {
    private final NotificationUseCase notifications;
    public AlertScheduling(NotificationUseCase notifications) { this.notifications = notifications; }
    @Scheduled(fixedDelayString = "${news.alert.delay-ms:60000}")
    public void tick() { notifications.flush(); }
}
