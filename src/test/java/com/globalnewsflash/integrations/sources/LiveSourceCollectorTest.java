package com.globalnewsflash.integrations.sources;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.ingestion.domain.SourceSpec;
import com.globalnewsflash.ingestion.infrastructure.LiveIngestionProperties;
import com.globalnewsflash.ingestion.port.out.SourceCollector;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LiveSourceCollectorTest {
    static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");

    @ParameterizedTest @ValueSource(strings = {"RSS", "FED"})
    void oneBrokenArticleDoesNotDiscardHealthyArticlesAndQuarantineSkipsNetwork(String type) {
        var fetcher = mock(BoundedHttpsFetcher.class);
        var collector = new LiveSourceCollector(new LiveIngestionProperties(), JsonMapper.builder().build(), fetcher);
        var source = source(type);
        String good = "https://www.federalreserve.gov/newsevents/pressreleases/good.htm";
        String bad = "https://www.federalreserve.gov/newsevents/pressreleases/bad.htm";
        when(fetcher.fetch(eq(good), anySet(), isNull(), isNull(), any()))
                .thenReturn(new BoundedHttpsFetcher.Response(200, "<article><p>" + "A detailed official announcement. ".repeat(12) + "</p></article>", null, null));
        when(fetcher.fetch(eq(bad), anySet(), isNull(), isNull(), any()))
                .thenThrow(new BoundedHttpsFetcher.FetchFailure("SOURCE_TIMEOUT"));
        String payload = "<rss version='2.0'><channel><title>News</title>" + item(good) + item(bad) + "</channel></rss>";
        var snapshot = new SourceCollector.Snapshot(payload, "e", null, false);
        var policy = new SourcePolicy(source.id(), true, true, false, "test", Set.of(source.producer()));
        var batch = collector.collect(source, policy, snapshot, 0, NOW, () -> {});
        assertThat(batch.materials()).hasSize(1);
        assertThat(batch.failures()).singleElement().satisfies(f -> assertThat(f.code()).isEqualTo("SOURCE_TIMEOUT"));
        assertThat(batch.nextOffset()).isEqualTo(2);
        String itemKey = batch.failures().getFirst().itemKey();
        collector.collect(source, policy, new SourceCollector.Snapshot(payload, "e", null, false, Set.of(itemKey)), 0, NOW, () -> {});
        verify(fetcher, times(1)).fetch(eq(bad), anySet(), isNull(), isNull(), any());
        verify(fetcher, times(2)).fetch(eq(good), anySet(), isNull(), isNull(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"RSS", "FED"})
    void lostHeartbeatAbortsInsteadOfQuarantiningArticle(String type) {
        var fetcher = mock(BoundedHttpsFetcher.class);
        var collector = new LiveSourceCollector(new LiveIngestionProperties(), JsonMapper.builder().build(), fetcher);
        when(fetcher.fetch(anyString(), anySet(), isNull(), isNull(), any())).thenThrow(new SourceCollector.HeartbeatFailure());
        var source = source(type);
        var policy = new SourcePolicy(source.id(), true, true, false, "test", Set.of(source.producer()));
        var snapshot = new SourceCollector.Snapshot("<rss version='2.0'><channel><title>News</title>"
                + item("https://www.federalreserve.gov/newsevents/pressreleases/good.htm") + "</channel></rss>", null, null, false);
        assertThatThrownBy(() -> collector.collect(source, policy, snapshot, 0, NOW, () -> {}))
                .isInstanceOf(SourceCollector.HeartbeatFailure.class);
    }

    private SourceSpec source(String type) {
        return new SourceSpec("FED".equals(type) ? "fed" : "rss-test", type, "https://www.federalreserve.gov/feed.xml", true, true,
                "test", "federal-reserve-board", Set.of("federal-reserve-board"), Set.of("www.federalreserve.gov"),
                "article", "OFFICIAL_ANNOUNCEMENT", true);
    }
    private String item(String url) {
        return "<item><title>Official announcement</title><guid>" + url + "</guid><link>" + url
                + "</link><pubDate>Fri, 30 Oct 2026 03:00:00 GMT</pubDate><description>Announcement</description></item>";
    }
}
