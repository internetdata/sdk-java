package io.internetdata;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * An {@link HttpClient} that answers from a table keyed by request PATH, and counts what it was
 * asked for, so "this issued exactly one request" is asserted rather than assumed.
 */
final class StubHttpClient extends HttpClient {
    static final class Route {
        final int status;
        final byte[] body;
        final Map<String, String> headers;

        Route(int status, String body, Map<String, String> headers) {
            this(status, body.getBytes(StandardCharsets.UTF_8), headers);
        }

        /** A binary answer, for the database transfers, whose bytes are not text at all. */
        Route(int status, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        static Route ok(String body) {
            return new Route(200, body, Map.of());
        }
    }

    /** Every request URI, in order. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** The {@code Authorization} header of each call, or null, positionally matching {@link #calls}. */
    final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

    private final Map<String, Route> routes;

    private StubHttpClient(Map<String, Route> routes) {
        this.routes = new HashMap<>(routes);
    }

    /** Answers the given paths, and rejects anything else the way the API would an unknown id. */
    static StubHttpClient of(Map<String, Route> routes) {
        return new StubHttpClient(routes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        URI uri = request.uri();
        calls.add(uri.toString());
        authorizations.add(request.headers().firstValue("Authorization").orElse(null));

        String path = uri.getPath();
        Route route = routes.getOrDefault(path.startsWith("/") ? path.substring(1) : path,
                new Route(404, "{\"rc\": \"UNKNOWN_DATASET\"}", Map.of()));
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("content-type", List.of("application/json"));
        route.headers.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.of(v)));
        return (HttpResponse<T>) new StubResponse(request, route.status,
                HttpHeaders.of(headers, (a, b) -> true), new ByteArrayInputStream(route.body));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return CompletableFuture.supplyAsync(() -> send(request, handler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    private static final class StubResponse implements HttpResponse<InputStream> {
        private final HttpRequest request;
        private final int status;
        private final HttpHeaders headers;
        private final InputStream body;

        StubResponse(HttpRequest request, int status, HttpHeaders headers, InputStream body) {
            this.request = request;
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }
}
