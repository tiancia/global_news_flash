package com.globalnewsflash.digest.port.out;

import java.util.List;

/** Read-only operating inputs supplied by an infrastructure adapter. */
public interface DigestSettings {
    List<String> problems();
    boolean isEnabled();
    int getUtcHour();
    int getMaxItems();
    int getMaxCharacters();
}
