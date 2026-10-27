package com.globalnewsflash.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.globalnewsflash.automation.port.out.NotificationGateway;
import com.globalnewsflash.editorial.port.out.EditorPort;
import com.globalnewsflash.integrations.ai.DeepSeekEditor;
import com.globalnewsflash.integrations.ai.DeepSeekProperties;
import com.globalnewsflash.integrations.ai.OpenAiProperties;
import com.globalnewsflash.integrations.telegram.TelegramDeliveryGateway;
import com.globalnewsflash.integrations.telegram.TelegramProperties;
import com.globalnewsflash.integrations.webhook.AlertProperties;
import com.globalnewsflash.integrations.webhook.WebhookNotificationGateway;
import com.globalnewsflash.publication.port.out.DeliveryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** Checks component discovery and the existing configuration keys without allowing external traffic. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:integration-wiring;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "news.runtime.mode=LIVE", "news.runtime.external-enabled=false", "news.runtime.preferred-provider=deepseek",
        "news.clock.fixed=", "news.ingestion.enabled=false", "news.automation.enabled=false",
        "news.telegram.enabled=false", "news.publication.worker-enabled=false", "news.digest.enabled=false",
        "news.alert.enabled=false", "news.openai.model=wiring-openai", "news.deepseek.model=wiring-deepseek",
        "news.telegram.chat-id=-1001234567890", "news.alert.webhook-url=https://alerts.example.org/receive"
})
@ActiveProfiles("test")
class IntegrationWiringTest {
    @Autowired ApplicationContext context;

    @Test void relocatedClientsAreDiscoveredAndExistingConfigurationStillBinds() {
        assertThat(context.getBeansOfType(EditorPort.class).values()).singleElement().isInstanceOf(DeepSeekEditor.class);
        assertThat(context.getBeansOfType(DeliveryGateway.class).values()).singleElement().isInstanceOf(TelegramDeliveryGateway.class);
        assertThat(context.getBeansOfType(NotificationGateway.class).values()).singleElement().isInstanceOf(WebhookNotificationGateway.class);
        assertThat(context.getBean(OpenAiProperties.class).getModel()).isEqualTo("wiring-openai");
        assertThat(context.getBean(DeepSeekProperties.class).getModel()).isEqualTo("wiring-deepseek");
        assertThat(context.getBean(TelegramProperties.class).getChatId()).isEqualTo("-1001234567890");
        assertThat(context.getBean(AlertProperties.class).getWebhookUrl()).isEqualTo("https://alerts.example.org/receive");
    }
}
