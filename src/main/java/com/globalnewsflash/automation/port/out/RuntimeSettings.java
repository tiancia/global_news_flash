package com.globalnewsflash.automation.port.out;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface RuntimeSettings {
    String getMode();
    boolean isExternalEnabled();
    String getPreferredProvider();
    boolean live();
    static RuntimeSettings offline() {
        return new RuntimeSettings() {
            public String getMode() { return "OFFLINE"; }
            public boolean isExternalEnabled() { return false; }
            public String getPreferredProvider() { return "openai"; }
            public boolean live() { return false; }
        };
    }

}
