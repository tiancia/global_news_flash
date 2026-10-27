package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.automation.port.out.RuntimeSettings;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External clients require both LIVE mode and an explicit external-access switch. */
@ConfigurationProperties("news.runtime")
public class RuntimeProperties implements RuntimeSettings {
    private String mode = "OFFLINE";
    private boolean externalEnabled;
    private String preferredProvider = "openai";

    @PostConstruct
    public void validate() {
        if (!"OFFLINE".equals(mode) && !"LIVE".equals(mode)) {
            throw new IllegalStateException("NEWS_RUNTIME_MODE must be OFFLINE or LIVE");
        }
        if (!"openai".equals(preferredProvider) && !"deepseek".equals(preferredProvider)) {
            throw new IllegalStateException("NEWS_AI_PROVIDER must be openai or deepseek");
        }
        if ("OFFLINE".equals(mode) && externalEnabled) {
            throw new IllegalStateException("External access requires LIVE mode");
        }
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isExternalEnabled() { return externalEnabled; }
    public void setExternalEnabled(boolean externalEnabled) { this.externalEnabled = externalEnabled; }
    public String getPreferredProvider() { return preferredProvider; }
    public void setPreferredProvider(String preferredProvider) { this.preferredProvider = preferredProvider; }
    public boolean live() { return "LIVE".equals(mode) && externalEnabled; }
}
