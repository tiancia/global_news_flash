package com.globalnewsflash.ingestion.domain;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Syntactic normalization only: this class never resolves a hostname or fetches a URL. */
public final class CanonicalUrl {
    private static final Set<String> TRACKING = Set.of("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid");

    private CanonicalUrl() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new IllegalArgumentException("A bounded HTTPS URL is required");
        }
        try {
            URI uri = new URI(value.trim()).normalize();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || host == null) {
                throw new IllegalArgumentException("Only HTTPS URLs without credentials are accepted");
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            rejectLocalHost(host);
            if (uri.getPort() != -1 && uri.getPort() != 443) {
                throw new IllegalArgumentException("Only the standard HTTPS port is accepted");
            }
            String query = uri.getRawQuery();
            if (query != null) {
                query = Arrays.stream(query.split("&", -1)).filter(part -> !isTracking(part))
                        .collect(Collectors.joining("&"));
                if (query.isEmpty()) query = null;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            // Preserve raw path/query escapes. The multi-argument URI constructor would double-escape them.
            return new URI("https://" + host + path + (query == null ? "" : "?" + query)).toASCIIString();
        } catch (URISyntaxException | java.net.UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid public HTTPS URL", exception);
        }
    }

    private static boolean isTracking(String pair) {
        String rawKey = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
        String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return key.startsWith("utm_") || TRACKING.contains(key);
    }

    private static void rejectLocalHost(String host) throws java.net.UnknownHostException {
        String plain = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (plain.equals("localhost") || plain.endsWith(".localhost") || plain.endsWith(".local")
                || plain.endsWith(".internal") || !plain.contains(".") && !plain.contains(":")) {
            throw new IllegalArgumentException("Local hostnames are not accepted");
        }
        if (plain.contains(":")) {
            // Literal IPv6 only. No DNS request is made for domain names.
            if (plain.contains("%")) throw new IllegalArgumentException("Scoped IP addresses are not accepted");
            var address = InetAddress.getByName(plain);
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc
                    || bytes.length == 4 && isPrivateV4(bytes)) {
                throw new IllegalArgumentException("Non-public IP addresses are not accepted");
            }
        } else if (plain.matches("[0-9.]+")) {
            String[] octets = plain.split("\\.", -1);
            if (octets.length != 4) throw new IllegalArgumentException("Ambiguous numeric host");
            byte[] bytes = new byte[4];
            for (int index = 0; index < 4; index++) {
                if (octets[index].length() > 1 && octets[index].startsWith("0")) {
                    throw new IllegalArgumentException("Ambiguous IP address");
                }
                int number;
                try { number = Integer.parseInt(octets[index]); }
                catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid IP address", exception); }
                if (number < 0 || number > 255) throw new IllegalArgumentException("Invalid IP address");
                bytes[index] = (byte) number;
            }
            if (isPrivateV4(bytes)) throw new IllegalArgumentException("Non-public IP addresses are not accepted");
        }
    }

    private static boolean isPrivateV4(byte[] bytes) {
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        return first == 0 || first == 10 || first == 127 || first >= 224
                || first == 169 && second == 254 || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168 || first == 100 && second >= 64 && second <= 127
                || first == 192 && second == 0
                || first == 198 && (second == 18 || second == 19 || second == 51 && (bytes[2] & 255) == 100)
                || first == 203 && second == 0 && (bytes[2] & 255) == 113;
    }
}
