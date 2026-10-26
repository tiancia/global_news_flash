package com.globalnewsflash.digest.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("news.digest")
public class DigestProperties {
    private boolean enabled;
    private int utcHour = 22;
    private int maxItems = 5;
    private int maxCharacters = 3000;

    public List<String> problems() {
        var reasons = new ArrayList<String>();
        if (utcHour < 0 || utcHour > 23) reasons.add("DIGEST_HOUR_INVALID");
        if (maxItems < 1 || maxItems > 20) reasons.add("DIGEST_ITEM_LIMIT_INVALID");
        if (maxCharacters < 200 || maxCharacters > 4096) reasons.add("DIGEST_LENGTH_INVALID");
        return List.copyOf(reasons);
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getUtcHour() { return utcHour; }
    public void setUtcHour(int value) { utcHour = value; }
    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int value) { maxItems = value; }
    public int getMaxCharacters() { return maxCharacters; }
    public void setMaxCharacters(int value) { maxCharacters = value; }
}
