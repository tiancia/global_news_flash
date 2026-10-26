package com.globalnewsflash.publication.infrastructure;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Secrets are only supplied through external configuration and never included in diagnostics. */
@Component
@ConfigurationProperties("news.telegram")
public final class TelegramProperties {
    private boolean enabled;
    private String botToken = "";
    private String chatId = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private Duration permissionCacheTtl = Duration.ofSeconds(60);
    private int maxResponseBytes = 262_144;

    @PostConstruct
    public void validate() {
        if (connectTimeout == null || connectTimeout.toMillis() < 1
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || requestTimeout == null || requestTimeout.toMillis() < 1
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || permissionCacheTtl == null || permissionCacheTtl.isNegative()
                || permissionCacheTtl.compareTo(Duration.ofMinutes(5)) > 0
                || maxResponseBytes < 4096 || maxResponseBytes > 1_048_576) {
            throw new IllegalStateException("Invalid Telegram transport limits");
        }
    }

    public boolean isConfigured() {
        return botToken != null && botToken.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,100}")
                && chatId != null && (chatId.matches("-100[0-9]{5,17}")
                || chatId.matches("@[A-Za-z][A-Za-z0-9_]{4,31}"));
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBotToken() { return botToken; }
    public void setBotToken(String botToken) { this.botToken = botToken; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getPermissionCacheTtl() { return permissionCacheTtl; }
    public void setPermissionCacheTtl(Duration permissionCacheTtl) { this.permissionCacheTtl = permissionCacheTtl; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
}
