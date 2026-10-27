package com.globalnewsflash.ingestion.infrastructure;

import com.globalnewsflash.shared.BoundedBodySubscriber;
import com.globalnewsflash.ingestion.domain.CanonicalUrl;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTPS only, exact host allowlist, no redirects, bounded complete response, and sanitized failures. */
final class BoundedHttpsFetcher {
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                var thread = new Thread(runnable, "news-source-dns");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    record Response(int status, String body, String etag, String lastModified) {}
    static final class FetchFailure extends RuntimeException {
        private final String code;
        FetchFailure(String code) { super(code); this.code = code; }
        String code() { return code; }
    }

    private final LiveIngestionProperties properties;
    private final HttpClient client;

    BoundedHttpsFetcher(LiveIngestionProperties properties) {
        this.properties = properties;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    Response fetch(String url, Set<String> allowedHosts, String etag, String modified, Runnable heartbeat) {
        // Keep lease callbacks outside the transport error handler so loss of the automation lease aborts the cycle.
        heartbeat.run();
        Response response = fetch(url, allowedHosts, etag, modified);
        heartbeat.run();
        return response;
    }

    Response fetch(String url, Set<String> allowedHosts, String etag, String modified) {
        try {
            URI uri = URI.create(CanonicalUrl.normalize(url));
            if (!"https".equalsIgnoreCase(uri.getScheme())) throw new FetchFailure("HTTPS_REQUIRED");
            if (!allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw new FetchFailure("HOST_NOT_ALLOWED");
            // An allowlisted hostname must not currently resolve to a private or special-use address.
            for (InetAddress address : addresses(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new FetchFailure("HOST_NOT_PUBLIC");
                CanonicalUrl.normalize("https://" + (address.getAddress().length == 16
                        ? "[" + address.getHostAddress() + "]" : address.getHostAddress()) + "/");
            }
            var request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("User-Agent", "GlobalNewsFlash/0.1 (+bounded-news-collector)")
                    .header("Accept", "application/geo+json, application/json, application/rss+xml, application/atom+xml, application/xml, text/xml, text/html")
                    .header("Accept-Encoding", "identity");
            if (etag != null && !etag.isBlank()) request.header("If-None-Match", header(etag, 512));
            if (modified != null && !modified.isBlank()) request.header("If-Modified-Since", header(modified, 256));
            HttpResponse<byte[]> response = send(request.build());
            if (response.statusCode() == 304) return new Response(304, "", etag, modified);
            if (response.statusCode() != 200) {
                if (response.statusCode() >= 300 && response.statusCode() < 400) throw new FetchFailure("REDIRECT_REFUSED");
                if (response.statusCode() == 429) throw new FetchFailure("SOURCE_RATE_LIMITED");
                throw new FetchFailure("SOURCE_HTTP_ERROR");
            }
            if (!response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")) {
                throw new FetchFailure("UNSUPPORTED_CONTENT_ENCODING");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            Charset charset = StandardCharsets.UTF_8;
            for (String part : contentType.split(";")) {
                if (part.strip().toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    charset = Charset.forName(part.strip().substring(8).replace("\"", ""));
                }
            }
            return new Response(200, new String(response.body(), charset),
                    response.headers().firstValue("ETag").map(value -> header(value, 512)).orElse(null),
                    response.headers().firstValue("Last-Modified").map(value -> header(value, 256)).orElse(null));
        } catch (FetchFailure failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FetchFailure("SOURCE_INTERRUPTED");
        } catch (Exception failure) {
            // Do not retain causes: URLs, credentials in query strings, and provider bodies are not operational errors.
            throw new FetchFailure("SOURCE_FETCH_FAILED");
        }
    }

    private InetAddress[] addresses(String host) throws InterruptedException {
        var future = DNS.submit(() -> InetAddress.getAllByName(host));
        try {
            return future.get(properties.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            DNS.purge();
            throw new FetchFailure("SOURCE_DNS_TIMEOUT");
        } catch (ExecutionException failed) {
            throw new FetchFailure("SOURCE_DNS_FAILED");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            DNS.purge();
            throw interrupted;
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws InterruptedException {
        var future = client.sendAsync(request, ignored -> new BoundedBodySubscriber(properties.getMaxResponseBytes(),
                () -> new FetchFailure("SOURCE_BODY_TOO_LARGE"), () -> new FetchFailure("SOURCE_FETCH_FAILED")));
        try {
            // The future completes only after the bounded subscriber receives the complete body.
            return future.get(properties.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new FetchFailure("SOURCE_TIMEOUT");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof FetchFailure known) throw known;
            if (failed.getCause() instanceof java.net.http.HttpTimeoutException) throw new FetchFailure("SOURCE_TIMEOUT");
            throw new FetchFailure("SOURCE_FETCH_FAILED");
        }
    }

    private static String header(String value, int max) {
        if (value.length() > max || value.contains("\r") || value.contains("\n")) throw new FetchFailure("INVALID_SOURCE_HEADER");
        return value;
    }

}
