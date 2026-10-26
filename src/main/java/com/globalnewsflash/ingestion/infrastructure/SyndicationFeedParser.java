package com.globalnewsflash.ingestion.infrastructure;

import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** RSS 2.0 and Atom 1.0 metadata plus explicitly designated inline content; summaries are never body. */
final class SyndicationFeedParser {
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String CONTENT = "http://purl.org/rss/1.0/modules/content/";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    record Entry(String externalId, String url, String title, Instant publishedAt, Instant updatedAt, String body) {}

    List<Entry> parse(String payload, String feedUrl) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
                @Override public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
            });
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are forbidden"); });
            Element root = builder.parse(new InputSource(new StringReader(payload))).getDocumentElement();
            if ("true".equals(root.getAttribute("synthetic"))) throw failure();
            if (matches(root, "", "rss") && "2.0".equals(root.getAttribute("version"))) {
                Element channel = child(root, "", "channel");
                if (channel == null) throw failure();
                var entries = children(channel, "", "item");
                bounded(entries);
                var result = new ArrayList<Entry>();
                for (Element item : entries) {
                    String link = required(text(child(item, "", "link")));
                    String guid = text(child(item, "", "guid"));
                    String title = required(text(child(item, "", "title")));
                    String body = text(child(item, CONTENT, "encoded"));
                    String updated = text(child(item, ATOM, "updated"));
                    if (updated == null) updated = text(child(item, DCTERMS, "modified"));
                    result.add(new Entry(guid == null ? link : guid, resolve(feedUrl, link), boundedTitle(title),
                            date(text(child(item, "", "pubDate"))), date(updated), body));
                }
                return unique(result);
            }
            if (matches(root, ATOM, "feed")) {
                var entries = children(root, ATOM, "entry");
                bounded(entries);
                var result = new ArrayList<Entry>();
                for (Element entry : entries) {
                    String link = null;
                    for (Element candidate : children(entry, ATOM, "link")) {
                        if (candidate.getAttribute("rel").isBlank() || "alternate".equals(candidate.getAttribute("rel"))) {
                            String type = candidate.getAttribute("type");
                            if (type.isBlank() || "text/html".equals(type) || "application/xhtml+xml".equals(type)) {
                                if (link != null) throw failure();
                                link = required(candidate.getAttribute("href"));
                            }
                        }
                    }
                    Element content = child(entry, ATOM, "content");
                    String body = null;
                    if (content != null && !content.hasAttribute("src")) {
                        String type = content.getAttribute("type");
                        if (type.isBlank() || "text".equals(type) || "html".equals(type) || "xhtml".equals(type)) {
                            body = content.getTextContent();
                        }
                    }
                    result.add(new Entry(required(text(child(entry, ATOM, "id"))), resolve(feedUrl, required(link)),
                            boundedTitle(required(text(child(entry, ATOM, "title")))),
                            date(text(child(entry, ATOM, "published"))), date(text(child(entry, ATOM, "updated"))), body));
                }
                return unique(result);
            }
            throw failure();
        } catch (BoundedHttpsFetcher.FetchFailure failure) { throw failure; }
        catch (Exception invalid) { throw failure(); }
    }

    private static void bounded(List<Element> entries) {
        if (entries.size() > PayloadLimits.MAX_ITEMS) throw new BoundedHttpsFetcher.FetchFailure("SOURCE_ITEM_LIMIT_EXCEEDED");
    }
    private static List<Entry> unique(List<Entry> entries) {
        if (entries.stream().map(Entry::externalId).distinct().count() != entries.size()) throw failure();
        return List.copyOf(entries);
    }
    private static String resolve(String feedUrl, String link) {
        return CanonicalUrl.normalize(URI.create(feedUrl).resolve(link).toString());
    }
    private static String boundedTitle(String title) {
        if (title.length() > 1_000) throw failure();
        return title;
    }
    private static Instant date(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }
    private static String text(Element element) {
        if (element == null || element.getTextContent().isBlank()) return null;
        return element.getTextContent().strip();
    }
    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 100_000) throw failure();
        return value.strip();
    }
    private static Element child(Element parent, String namespace, String name) {
        var matches = children(parent, namespace, name);
        if (matches.size() > 1) throw failure();
        return matches.isEmpty() ? null : matches.getFirst();
    }
    private static List<Element> children(Element parent, String namespace, String name) {
        var result = new ArrayList<Element>();
        for (Node item = parent.getFirstChild(); item != null; item = item.getNextSibling()) {
            if (item instanceof Element element && matches(element, namespace, name)) result.add(element);
        }
        return result;
    }
    private static boolean matches(Element element, String namespace, String name) {
        String actualNamespace = element.getNamespaceURI() == null ? "" : element.getNamespaceURI();
        return namespace.equals(actualNamespace) && name.equals(element.getLocalName());
    }
    private static BoundedHttpsFetcher.FetchFailure failure() {
        return new BoundedHttpsFetcher.FetchFailure("INVALID_SYNDICATION_FEED");
    }
}
