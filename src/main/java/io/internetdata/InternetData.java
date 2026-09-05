package io.internetdata;

import io.internetdata.internal.ApiClient;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A client for the InternetData API.
 *
 * <p>Build one with {@link #builder()} and keep it: it owns a connection pool, which is wasted if
 * it is rebuilt per request. It is thread safe.
 *
 * <p>Everything the API offers hangs off {@link #database()}. Every database published today is
 * licensed, so those calls want an API key carrying the {@code db.download} scope; the key is
 * optional nonetheless, and a client built without one sends no {@code Authorization} header at
 * all. What this API serves without a licence is a product decision, not the client's to refuse.
 */
public final class InternetData {
    public static final String DEFAULT_BASE_URL = "https://internetdata.io";

    private final DatabaseApi database;

    private InternetData(Builder b) {
        HttpClient http = b.httpClient != null ? b.httpClient : defaultHttpClient();
        ApiClient client = new ApiClient(new FixedHttpClientBuilder(http),
                ApiClient.createDefaultObjectMapper(), b.baseUrl);
        client.setReadTimeout(b.requestTimeout);
        if (b.apiKey != null && !b.apiKey.isEmpty()) {
            // The `native` generator emits no auth plumbing at all - it ignores the spec's
            // securitySchemes - so the key goes on by hand. Skipped entirely without one:
            // `Authorization: Bearer ` with nothing after it reads as a wrong key, not none.
            client.setRequestInterceptor(rb -> rb.header("Authorization", "Bearer " + b.apiKey));
        }

        this.database = new DatabaseApi(client, b.retries);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A client with every default, for the given API key. */
    public static InternetData create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** A client with every default and no key, which reaches only what needs no licence. */
    public static InternetData create() {
        return builder().build();
    }

    /**
     * The database endpoints: what you may see, what is inside one, and how to fetch it.
     *
     * <p>They sit behind a second level rather than on this class because the sibling VPNDetection
     * library keeps the same calls under {@code client.database()}, and a reader holding both
     * should not have to remember which brand spells the same operation which way.
     */
    public DatabaseApi database() {
        return database;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // DatabaseApi.downloadUrl reads the Location off a 302 rather than following it.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Settings for an {@link InternetData} client. Every one of them has a working default. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private int retries = 2;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient;

        private Builder() {}

        /**
         * Your API key, from the console, carrying the {@code db.download} scope.
         *
         * <p>Leave it unset to send no {@code Authorization} header at all, which reaches only
         * what the API serves without a licence.
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** Retry attempts for a transient failure. Default 2. */
        public Builder retries(int retries) {
            if (retries < 0) {
                throw new IllegalArgumentException("retries cannot be negative");
            }
            this.retries = retries;
            return this;
        }

        /**
         * How long one API call may take before it is abandoned. Default 30 seconds.
         *
         * <p>This does NOT bound a file transfer, which is unbounded on purpose: a multi-gigabyte
         * download is a different kind of wait from a metadata request.
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        /**
         * Use a specific {@link HttpClient}, for a proxy, a custom SSL context or a test double.
         *
         * <p>It must NOT follow redirects, or {@link DatabaseApi#downloadUrl} will fetch the whole
         * database instead of returning its link.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        public InternetData build() {
            return new InternetData(this);
        }
    }

    /**
     * Hands the generated client one already-built {@link HttpClient}.
     *
     * <p>{@code ApiClient} only accepts a {@link HttpClient.Builder} and calls {@code build()} once
     * per generated API class, so a real builder would produce a second client, with its own
     * selector thread and connection pool, for every API added here. It also makes a counting test
     * double injectable.
     */
    private static final class FixedHttpClientBuilder implements HttpClient.Builder {
        private final HttpClient client;

        FixedHttpClientBuilder(HttpClient client) {
            this.client = client;
        }

        @Override
        public HttpClient build() {
            return client;
        }

        @Override
        public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
            return this;
        }

        @Override
        public HttpClient.Builder connectTimeout(Duration duration) {
            return this;
        }

        @Override
        public HttpClient.Builder sslContext(SSLContext sslContext) {
            return this;
        }

        @Override
        public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
            return this;
        }

        @Override
        public HttpClient.Builder executor(Executor executor) {
            return this;
        }

        @Override
        public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
            return this;
        }

        @Override
        public HttpClient.Builder version(HttpClient.Version version) {
            return this;
        }

        @Override
        public HttpClient.Builder priority(int priority) {
            return this;
        }

        @Override
        public HttpClient.Builder proxy(ProxySelector proxySelector) {
            return this;
        }

        @Override
        public HttpClient.Builder authenticator(Authenticator authenticator) {
            return this;
        }
    }
}
