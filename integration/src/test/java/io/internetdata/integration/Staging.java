package io.internetdata.integration;

import io.internetdata.InternetData;

import java.net.URI;
import java.net.http.HttpClient;

/** The one client the suite shares, and the environment it is pointed at. */
final class Staging {
    private Staging() {}

    /**
     * Overridable so the same suite can be pointed at another deployment, but staging by default:
     * the license behind the CI key is provisioned there, and a run against production would move
     * real bytes for a credential CI holds.
     */
    static final String BASE_URL = envOr("INTERNETDATA_BASE_URL", "https://staging.internetdata.io");

    static final String HOST = URI.create(BASE_URL).getHost();

    /** The client, and the recorder underneath it. */
    record Probe(InternetData client, RecordingHttpClient recorder) {}

    private static Probe probe;

    /**
     * Why the suite cannot run, or null. Empty counts as absent: Actions interpolates a secret that
     * does not exist to an empty string, and an empty key is sent as no key at all.
     */
    static String skipReason() {
        return key().isEmpty()
                ? "INTERNETDATA_STAGING_KEY is not set; every endpoint here is licensed"
                : null;
    }

    /** One client for the whole run, so the tests share a connection pool rather than a key. */
    static Probe probe() {
        if (probe == null) {
            RecordingHttpClient recorder = new RecordingHttpClient(
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                    key());
            probe = new Probe(
                    InternetData.builder().baseUrl(BASE_URL).apiKey(key()).httpClient(recorder).build(),
                    recorder);
        }
        return probe;
    }

    static InternetData client() {
        return probe().client();
    }

    private static String key() {
        return envOr("INTERNETDATA_STAGING_KEY", "");
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
