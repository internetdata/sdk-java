package io.internetdata.integration;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A real {@link HttpClient} that remembers what it was asked for.
 *
 * <p>The JDK has no request hook, so the recorder is the client: it delegates every call and notes
 * a few derived facts on the way past.
 *
 * <p>Nothing but derived facts leaves here, and no response body is ever held. A failing assertion
 * prints its operands, so keeping the request itself is how a key ends up in a public CI log;
 * whether the key was carried is a boolean the caller can print safely. Holding a body would be the
 * multi-gigabyte mistake the library exists to avoid, since a database transfer runs through this
 * same client.
 */
final class RecordingHttpClient extends HttpClient {
    record Fact(String host, String path, boolean carriedKey) {}

    private final HttpClient delegate;
    private final String key;
    private final List<Fact> facts = Collections.synchronizedList(new ArrayList<>());

    RecordingHttpClient(HttpClient delegate, String key) {
        this.delegate = delegate;
        this.key = key;
    }

    List<Fact> facts() {
        return List.copyOf(facts);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        note(request);
        return delegate.send(request, handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        note(request);
        return delegate.sendAsync(request, handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        note(request);
        return delegate.sendAsync(request, handler, pushPromiseHandler);
    }

    private void note(HttpRequest request) {
        URI uri = request.uri();
        boolean carried = uri.toString().contains(key);
        for (List<String> values : request.headers().map().values()) {
            carried = carried || values.stream().anyMatch(value -> value.contains(key));
        }
        facts.add(new Fact(uri.getHost(), uri.getPath(), carried));
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }
}
