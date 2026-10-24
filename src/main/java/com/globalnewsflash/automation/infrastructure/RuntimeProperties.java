package com.globalnewsflash.automation.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** This development build deliberately has no live adapter or live execution mode. */
@ConfigurationProperties("news.runtime")
public class RuntimeProperties {
    private String mode = "OFFLINE";
    private boolean externalEnabled;
    private String preferredProvider = "openai";

    @PostConstruct
    public void validate() {
        if (!"OFFLINE".equals(mode) || externalEnabled) {
            throw new IllegalStateException("This build supports OFFLINE mode only; external access is unavailable");
        }
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isExternalEnabled() { return externalEnabled; }
    public void setExternalEnabled(boolean externalEnabled) { this.externalEnabled = externalEnabled; }
    public String getPreferredProvider() { return preferredProvider; }
    public void setPreferredProvider(String preferredProvider) { this.preferredProvider = preferredProvider; }
}
