package com.globalnewsflash.integrations.sources;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyndicationFeedParserTest {
    private final SyndicationFeedParser parser = new SyndicationFeedParser();
    private final String feedUrl = "https://news.example.org/feed.xml";

    @ParameterizedTest
    @ValueSource(strings = {"", "\uFEFF"})
    void rssPreservesMetadataAndDesignatedBodyWithOrWithoutBom(String prefix) {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                  <channel><item>
                    <guid>example-release-1</guid><title>Example release</title>
                    <link>https://news.example.org/releases/1</link>
                    <pubDate>Mon, 26 Oct 2026 12:00:00 GMT</pubDate>
                    <content:encoded><![CDATA[<p>Example announcement body.</p>]]></content:encoded>
                  </item></channel>
                </rss>
                """;
        assertThat(parser.parse(prefix + rss, feedUrl)).singleElement().satisfies(entry -> {
            assertThat(entry.externalId()).isEqualTo("example-release-1");
            assertThat(entry.url()).isEqualTo("https://news.example.org/releases/1");
            assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-10-26T12:00:00Z"));
            assertThat(entry.body()).isEqualTo("<p>Example announcement body.</p>");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\uFEFF"})
    void atomPreservesMetadataAndDesignatedBodyWithOrWithoutBom(String prefix) {
        String atom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"><entry>
                  <id>example-release-2</id><title>Example release</title>
                  <link href="https://news.example.org/releases/2"/>
                  <published>2026-10-26T12:00:00Z</published>
                  <updated>2026-10-26T13:00:00Z</updated>
                  <content type="html"><![CDATA[<p>Example announcement body.</p>]]></content>
                </entry></feed>
                """;
        assertThat(parser.parse(prefix + atom, feedUrl)).singleElement().satisfies(entry -> {
            assertThat(entry.externalId()).isEqualTo("example-release-2");
            assertThat(entry.url()).isEqualTo("https://news.example.org/releases/2");
            assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-10-26T12:00:00Z"));
            assertThat(entry.updatedAt()).isEqualTo(Instant.parse("2026-10-26T13:00:00Z"));
            assertThat(entry.body()).isEqualTo("<p>Example announcement body.</p>");
        });
    }

    @Test
    void bomDoesNotAllowExternalEntities() {
        String malicious = "\uFEFF" + """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY stolen SYSTEM "file:///C:/Windows/win.ini">]>
                <rss version="2.0"><channel><item><title>&stolen;</title>
                <link>https://news.example.org/releases/1</link></item></channel></rss>
                """;
        assertThatThrownBy(() -> parser.parse(malicious, feedUrl))
                .isInstanceOf(BoundedHttpsFetcher.FetchFailure.class)
                .hasMessage("INVALID_SYNDICATION_FEED");
    }
}
