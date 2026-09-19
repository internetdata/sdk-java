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
 * all. What this API serves without a license is a product decision, not the client's to refuse.
 */
public final class InternetData {
    public static final String DEFAULT_BASE_URL = "https://internetdata.io";

    private final DatabaseApi database;

    private InternetData(Builder b) {
        HttpClient http = new DeadlineHttpClient(b.httpClient != null ? b.httpClient : defaultHttpClient());
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

    /** A client with every default and no key, which reaches only what needs no license. */
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
         * what the API serves without a license.
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Where the API is served. Default {@value InternetData#DEFAULT_BASE_URL}.
         *
         * <p>A trailing slash is dropped. Every path this client appends begins with one, and a
         * doubled slash is a different path to the server: production answers it with a redirect,
         * which this client does not follow, so every call would fail.
         */
        public Builder baseUrl(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl");
            this.baseUrl = baseUrl.replaceAll("/+$", "");
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
         * How long one attempt at an API call may take, response body included, before it is
         * abandoned as a retryable {@link ErrorKind#NETWORK} failure. Default 30 seconds.
         *
         * <p>Per ATTEMPT, so a call that is retried can take longer in total. This does NOT bound a
         * file transfer, which is unbounded on purpose: a multi-gigabyte download is a different
         * kind of wait from a metadata request.
         *
         * @throws IllegalArgumentException for zero, a negative duration, or one too long to count
         *     in nanoseconds (about 292 years). Accepted, each would fail EVERY call rather than
         *     bound it: the JDK refuses a request timeout that is not positive, and the deadline
         *     this client races is counted in nanoseconds.
         */
        public Builder requestTimeout(Duration requestTimeout) {
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            try {
                requestTimeout.toNanos();
            } catch (ArithmeticException tooLong) {
                throw new IllegalArgumentException(
                        "requestTimeout is too long to count in nanoseconds", tooLong);
            }
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Use a specific {@link HttpClient}, for a proxy, a custom SSL context or a test double.
         *
         * <p>It must NOT follow redirects, or {@link DatabaseApi#downloadUrl} will fetch the whole
         * database instead of returning its link. API calls go through its {@code sendAsync}, which
         * is how {@link #requestTimeout} holds even where the client itself ignores a request's
         * timeout.
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
