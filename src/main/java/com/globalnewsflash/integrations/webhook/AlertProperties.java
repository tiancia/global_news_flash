package com.globalnewsflash.integrations.webhook;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("news.alert")
public class AlertProperties {
    private boolean enabled;
    private String webhookUrl = "";
    private String webhookToken = "";
    private Duration timeout = Duration.ofSeconds(10);

    public boolean configured() {
        try {
            URI target = URI.create(webhookUrl);
            return "https".equalsIgnoreCase(target.getScheme()) && target.getHost() != null
                    && target.getUserInfo() == null && target.getFragment() == null
                    && timeout != null && timeout.isPositive() && timeout.compareTo(Duration.ofSeconds(30)) <= 0
                    && webhookToken != null && !webhookToken.contains("\r") && !webhookToken.contains("\n");
        } catch (RuntimeException invalid) { return false; }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String value) { webhookUrl = value; }
    public String getWebhookToken() { return webhookToken; }
    public void setWebhookToken(String value) { webhookToken = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
}
