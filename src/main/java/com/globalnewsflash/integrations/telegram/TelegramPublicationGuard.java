package com.globalnewsflash.integrations.telegram;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.publication.application.PublicationGuard;
import com.globalnewsflash.publication.domain.PublicationOperation;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "news.runtime.mode", havingValue = "LIVE")
public final class TelegramPublicationGuard implements PublicationGuard {
    private final RuntimeProperties runtime;
    private final TelegramProperties telegram;

    public TelegramPublicationGuard(RuntimeProperties runtime, TelegramProperties telegram) {
        this.runtime = runtime;
        this.telegram = telegram;
    }

    @Override
    public List<String> rejectionReasons(PublicationOperation operation) {
        if (!"LIVE".equals(runtime.getMode()) || !runtime.isExternalEnabled() || !telegram.isEnabled()) {
            return List.of("TELEGRAM_DELIVERY_DISABLED");
        }
        if (operation.synthetic()) return List.of("SYNTHETIC_PUBLICATION_IN_LIVE_MODE");
        if (!telegram.isConfigured()) return List.of("TELEGRAM_CONFIGURATION_REQUIRED");
        if (!telegram.getChatId().equals(operation.target())) return List.of("TELEGRAM_TARGET_NOT_ALLOWED");
        if (!TelegramMessageText.withinLimit(TelegramMessageText.render(operation))) {
            return List.of("TELEGRAM_TEXT_LIMIT_EXCEEDED");
        }
        return List.of();
    }
}
