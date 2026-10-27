package com.globalnewsflash.integrations.sources;

import com.globalnewsflash.ingestion.infrastructure.LiveIngestionProperties;
import com.globalnewsflash.ingestion.domain.SourceMaterial.Fact;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Extracts bounded source text. Extracted passages are source claims, not independent fact verification. */
final class ArticleTextExtractor {
    private final LiveIngestionProperties properties;
    ArticleTextExtractor(LiveIngestionProperties properties) { this.properties = properties; }

    List<Fact> article(String html, String selector) {
        if (selector == null || selector.isBlank()) throw new BoundedHttpsFetcher.FetchFailure("ARTICLE_SELECTOR_REQUIRED");
        try {
            var document = Jsoup.parse(html);
            document.select("script,style,noscript,nav,footer,header,aside,form,button,.related,.share,.social,.breadcrumb").remove();
            Element article = document.selectFirst(selector);
            if (article == null) throw new BoundedHttpsFetcher.FetchFailure("ARTICLE_BODY_NOT_FOUND");
            return passages(article);
        } catch (BoundedHttpsFetcher.FetchFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new BoundedHttpsFetcher.FetchFailure("ARTICLE_EXTRACTION_FAILED"); }
    }

    List<Fact> inline(String html) {
        var fragment = Jsoup.parseBodyFragment(html);
        fragment.select("script,style,noscript,nav,footer,header,aside,form,button").remove();
        return passages(fragment.body());
    }

    private List<Fact> passages(Element element) {
        element.select("p,div,section,li,blockquote,h1,h2,h3,h4,br").forEach(block -> block.appendText("\n"));
        // wholeText preserves block boundaries while text strips any executable markup.
        String text = element.wholeText().replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n[ ]+", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        if (text.length() < properties.getMinimumBodyCharacters()) return List.of();
        int end = Math.min(text.length(), properties.getMaxBodyCharacters());
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        text = text.substring(0, end);
        var facts = new ArrayList<Fact>();
        for (int start = 0; start < text.length();) {
            int boundary = Math.min(start + 1_500, text.length());
            if (boundary < text.length()) {
                int paragraph = text.lastIndexOf('\n', boundary);
                if (paragraph > start + 300) boundary = paragraph;
                else {
                    int space = text.lastIndexOf(' ', boundary);
                    if (space > start + 300) boundary = space;
                }
                if (boundary > start && Character.isHighSurrogate(text.charAt(boundary - 1))) boundary--;
            }
            String passage = text.substring(start, boundary).strip();
            if (!passage.isEmpty()) facts.add(new Fact("source-passage-" + (facts.size() + 1), passage));
            start = boundary;
        }
        return List.copyOf(facts);
    }
}
