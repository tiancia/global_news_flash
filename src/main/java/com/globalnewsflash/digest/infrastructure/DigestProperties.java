package com.globalnewsflash.digest.infrastructure;

import com.globalnewsflash.digest.port.out.DigestSettings;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("news.digest")
public class DigestProperties implements DigestSettings {
    private boolean enabled;
    private int utcHour = 22;
    private int maxItems = 5;
    private int maxCharacters = 3000;

    public List<String> problems() {
        return com.globalnewsflash.digest.domain.DigestPolicy.configurationProblems(utcHour, maxItems, maxCharacters);
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
