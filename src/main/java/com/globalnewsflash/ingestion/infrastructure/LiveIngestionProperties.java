package com.globalnewsflash.ingestion.infrastructure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External collection is opt-in; approval and licensing are independent of enabling a source. */
@ConfigurationProperties("news.ingestion")
public class LiveIngestionProperties {
    private boolean enabled;
    private long pollDelayMs = 60_000;
    private long sourcePollIntervalMs = 300_000;
    private int maxSourcesPerPoll = 10;
    private int maxItemsPerSource = 20;
    private int maxResponseBytes = 1_000_000;
    private int maxBodyCharacters = 16_000;
    private int minimumBodyCharacters = 200;
    private int requestTimeoutSeconds = 15;
    private int sourceLeaseSeconds = 900;
    private int inboxLeaseSeconds = 900;
    private int maxPendingItems = 2_000;
    private String rssFeedsJson = "[]";
    private Source usgs = new Source();
    private Source fed = new Source();
    private Source gdelt = new Source();

    public void validate() {
        if (pollDelayMs < 1_000 || sourcePollIntervalMs < 60_000 || maxSourcesPerPoll < 1 || maxSourcesPerPoll > 30
                || maxItemsPerSource < 1 || maxItemsPerSource > 100 || maxResponseBytes < 1024 || maxResponseBytes > 4_000_000
                || maxBodyCharacters < 200 || maxBodyCharacters > 48_000 || minimumBodyCharacters < 80
                || minimumBodyCharacters > maxBodyCharacters || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 60
                || sourceLeaseSeconds < 60 || sourceLeaseSeconds > 7_200 || inboxLeaseSeconds < 60 || inboxLeaseSeconds > 7_200
                || maxPendingItems < 1 || maxPendingItems > 100_000 || rssFeedsJson == null || rssFeedsJson.length() > 100_000) {
            throw new IllegalStateException("Invalid bounded ingestion configuration");
        }
        if (sourceLeaseSeconds <= 2L * (maxItemsPerSource + 1L) * requestTimeoutSeconds + 30L) {
            throw new IllegalStateException("Ingestion source lease must exceed the maximum fetch batch duration");
        }
    }

    public static class Source {
        private boolean enabled;
        private boolean approved;
        private String licenseVersion = "pending";
        private List<String> permittedProducers = List.of();
        private String url = "";
        private String articleSelector = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isApproved() { return approved; }
        public void setApproved(boolean approved) { this.approved = approved; }
        public String getLicenseVersion() { return licenseVersion; }
        public void setLicenseVersion(String licenseVersion) { this.licenseVersion = licenseVersion; }
        public List<String> getPermittedProducers() { return permittedProducers; }
        public void setPermittedProducers(List<String> permittedProducers) { this.permittedProducers = permittedProducers; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getArticleSelector() { return articleSelector; }
        public void setArticleSelector(String articleSelector) { this.articleSelector = articleSelector; }
    }

    /** JSON contract for explicitly configured third-party RSS/Atom sources. Summary is never body evidence. */
    public record FeedDefinition(String id, String url, boolean enabled, boolean approved, String licenseVersion,
            String producer, List<String> permittedProducers, List<String> allowedHosts, String articleSelector,
            String topic, boolean fetchArticle) {}

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollDelayMs() { return pollDelayMs; }
    public void setPollDelayMs(long pollDelayMs) { this.pollDelayMs = pollDelayMs; }
    public long getSourcePollIntervalMs() { return sourcePollIntervalMs; }
    public void setSourcePollIntervalMs(long value) { this.sourcePollIntervalMs = value; }
    public int getMaxSourcesPerPoll() { return maxSourcesPerPoll; }
    public void setMaxSourcesPerPoll(int value) { this.maxSourcesPerPoll = value; }
    public int getMaxItemsPerSource() { return maxItemsPerSource; }
    public void setMaxItemsPerSource(int value) { this.maxItemsPerSource = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { this.maxResponseBytes = value; }
    public int getMaxBodyCharacters() { return maxBodyCharacters; }
    public void setMaxBodyCharacters(int value) { this.maxBodyCharacters = value; }
    public int getMinimumBodyCharacters() { return minimumBodyCharacters; }
    public void setMinimumBodyCharacters(int value) { this.minimumBodyCharacters = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { this.requestTimeoutSeconds = value; }
    public int getSourceLeaseSeconds() { return sourceLeaseSeconds; }
    public void setSourceLeaseSeconds(int value) { this.sourceLeaseSeconds = value; }
    public int getInboxLeaseSeconds() { return inboxLeaseSeconds; }
    public void setInboxLeaseSeconds(int value) { this.inboxLeaseSeconds = value; }
    public int getMaxPendingItems() { return maxPendingItems; }
    public void setMaxPendingItems(int value) { this.maxPendingItems = value; }
    public String getRssFeedsJson() { return rssFeedsJson; }
    public void setRssFeedsJson(String value) { this.rssFeedsJson = value; }
    public Source getUsgs() { return usgs; }
    public void setUsgs(Source value) { this.usgs = value; }
    public Source getFed() { return fed; }
    public void setFed(Source value) { this.fed = value; }
    public Source getGdelt() { return gdelt; }
    public void setGdelt(Source value) { this.gdelt = value; }
}
