package com.globalnewsflash.publication.port.out;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface PublicationTarget {
    boolean isConfigured();
    boolean isEnabled();
    String getChatId();
    static PublicationTarget disabled() {
        return new PublicationTarget() {
            public boolean isEnabled() { return false; }
            public boolean isConfigured() { return false; }
            public String getChatId() { return ""; }
        };
    }

}
