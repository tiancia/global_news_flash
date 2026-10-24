package com.globalnewsflash.ingestion.infrastructure;

import com.globalnewsflash.ingestion.application.SourceAdapter;
import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

@Component
public final class FederalReserveRssAdapter implements SourceAdapter {
    @Override public String sourceId() { return "fed"; }

    @Override
    public List<SourceMaterial> parse(String payload, Instant discoveredAt, boolean synthetic) {
        PayloadLimits.check(payload, discoveredAt);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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
            var document = builder.parse(new InputSource(new StringReader(payload)));
            Element rss = document.getDocumentElement();
            if (!"rss".equals(rss.getTagName()) || !"2.0".equals(rss.getAttribute("version"))) {
                throw new IllegalArgumentException("An RSS document is required");
            }
            if (!synthetic && "true".equals(rss.getAttribute("synthetic"))) {
                throw new IllegalArgumentException("A synthetic fixture cannot be relabeled as real material");
            }
            var channels = rss.getElementsByTagName("channel");
            if (channels.getLength() != 1 || channels.item(0).getParentNode() != rss) {
                throw new IllegalArgumentException("A single RSS channel is required");
            }
            var items = document.getElementsByTagName("item");
            if (items.getLength() > PayloadLimits.MAX_ITEMS) throw new IllegalArgumentException("RSS item limit exceeded");
            var result = new ArrayList<SourceMaterial>();
            for (int index = 0; index < items.getLength(); index++) {
                Element item = (Element) items.item(index);
                if (item.getParentNode() != channels.item(0)) {
                    throw new IllegalArgumentException("RSS items must belong directly to the channel");
                }
                String title = requiredChild(item, "title");
                String url = CanonicalUrl.normalize(requiredChild(item, "link"));
                String host = URI.create(url).getHost();
                if (!"www.federalreserve.gov".equals(host) && !"federalreserve.gov".equals(host)) {
                    throw new IllegalArgumentException("Board announcement URLs must identify the official Board host");
                }
                String guid = child(item, "guid");
                if (guid == null) guid = url;
                String eventKey = StableEventKey.hashed(sourceId(), guid);
                String publication = child(item, "pubDate");
                Instant publishedAt = publication == null ? null
                        : ZonedDateTime.parse(publication, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                // An RSS title/description does not establish the detailed facts of an announcement.
                result.add(new SourceMaterial(sourceId(), guid, eventKey, url, title,
                        "OFFICIAL_ANNOUNCEMENT", "federal-reserve-board", eventKey,
                        null, publishedAt, null, discoveredAt, List.of(), false, synthetic));
            }
            return List.copyOf(result);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid RSS publication date", exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid or unsafe RSS payload", exception);
        }
    }

    private static String requiredChild(Element element, String name) {
        String value = child(element, name);
        if (value == null) throw new IllegalArgumentException("RSS field " + name + " is required");
        return value;
    }

    private static String child(Element element, String name) {
        String result = null;
        boolean found = false;
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && name.equals(child.getTagName())) {
                if (found) throw new IllegalArgumentException("Duplicate RSS field " + name);
                found = true;
                String text = child.getTextContent().strip();
                result = text.isEmpty() ? null : text;
            }
        }
        return result;
    }
}
