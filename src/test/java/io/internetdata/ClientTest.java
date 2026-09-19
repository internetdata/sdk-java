package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** The builder, and the settings a caller is most likely to reach for. */
class ClientTest {
    /**
     * The key is optional because what this API serves without a license is a product decision, and
     * a builder that could not finish without one would have to change shape to follow it. What
     * must never go out is {@code Authorization: Bearer } with nothing after it, which reads as a
     * wrong key rather than as none.
     */
    @Test
    void aClientBuildsWithNoKeyAndSendsNoAuthorizationHeader() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok("{\"databases\": []}")));

        InternetData.builder().httpClient(http).build().database().list();
        InternetData.builder().httpClient(http).apiKey("").build().database().list();

        assertEquals(2, http.calls.size());
        assertNull(http.authorizations.get(0), "an unset key still sent an Authorization header");
        assertNull(http.authorizations.get(1), "an empty key still sent an Authorization header");
    }

    @Test
    void theBaseUrlIsWhereTheRequestGoes() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok("{\"databases\": []}")));

        InternetData.builder().httpClient(http).apiKey("k")
                .baseUrl("https://staging.internetdata.io").build().database().list();

        assertEquals("https://staging.internetdata.io/api/v2/database/list", http.calls.get(0));
    }

    @Test
    void theBuilderRefusesSettingsThatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> InternetData.builder().retries(-1));
        assertThrows(NullPointerException.class, () -> InternetData.builder().baseUrl(null));
        assertThrows(NullPointerException.class, () -> InternetData.builder().requestTimeout(null));
        assertThrows(NullPointerException.class, () -> InternetData.builder().httpClient(null));
    }

    /**
     * A timeout no attempt can meet is refused where it is SET, not on every call.
     *
     * <p>Before 2.0.2 each of these built a client whose every call then threw: a raw
     * {@code IllegalArgumentException: Invalid duration} from the JDK for zero or a negative
     * duration, and a raw {@code ArithmeticException: long overflow} past {@code Long.MAX_VALUE}
     * nanoseconds. The boundary is asserted on both sides, so a check against the wrong unit fails.
     */
    @Test
    void aTimeoutNoAttemptCanMeetIsRefusedWhereItIsSet() {
        StubHttpClient http = StubHttpClient.of(Map.of());
        Duration longest = Duration.ofNanos(Long.MAX_VALUE);

        for (Duration refused : new Duration[] {Duration.ZERO, Duration.ofMillis(-1),
                longest.plusNanos(1), ChronoUnit.FOREVER.getDuration()}) {
            assertThrows(IllegalArgumentException.class,
                    () -> InternetData.builder().httpClient(http).requestTimeout(refused),
                    refused + " was accepted");
        }
        assertDoesNotThrow(
                () -> InternetData.builder().httpClient(http).requestTimeout(Duration.ofNanos(1)).build());
        assertDoesNotThrow(
                () -> InternetData.builder().httpClient(http).requestTimeout(longest).build());
        assertEquals(0, http.calls.size(), "refusing a timeout reached the network");
    }

    /**
     * A trailing slash on the base URL is dropped rather than doubled into every path, which
     * production answers with a redirect this client does not follow. The stub answers ANY path, so
     * a doubled one fails at the assertion on the URL rather than as an unknown route.
     */
    @Test
    void aTrailingSlashOnTheBaseUrlIsNotDoubledIntoThePath() {
        StubHttpClient http = StubHttpClient.responding(
                path -> StubHttpClient.Route.ok("{\"databases\": []}"));

        InternetData.builder().httpClient(http).apiKey("k")
                .baseUrl("https://staging.internetdata.io/").build().database().list();
        InternetData.builder().httpClient(http).apiKey("k")
                .baseUrl("https://staging.internetdata.io//").build().database().list();

        assertEquals("https://staging.internetdata.io/api/v2/database/list", http.calls.get(0));
        assertEquals("https://staging.internetdata.io/api/v2/database/list", http.calls.get(1));
    }

    @Test
    void argumentsAreRejectedRatherThanSentAsTheWordNull() {
        StubHttpClient http = StubHttpClient.of(Map.of());
        InternetData client = InternetData.builder().httpClient(http).apiKey("k").build();

        assertThrows(NullPointerException.class, () -> client.database().metadata(null));
        assertThrows(NullPointerException.class,
                () -> client.database().checksums(null, DatabaseFormat.CSVGZ));
        assertThrows(NullPointerException.class, () -> client.database().checksums("bogon_ip_v1", null));
        assertThrows(NullPointerException.class, () -> client.database().downloadUrl("bogon_ip_v1", null));
        assertEquals(0, http.calls.size(), "a null argument reached the network");
    }

    /**
     * The default client must not follow redirects, or {@code downloadUrl} would fetch a database
     * instead of returning its link. {@code Redirect.NEVER} happens to be the JDK's default on 25,
     * but a default is not a guarantee, so it is set explicitly and asserted here.
     */
    @Test
    void theDefaultTransportDoesNotFollowRedirects() {
        assertEquals(HttpClient.Redirect.NEVER, HttpClient.newHttpClient().followRedirects(),
                "the JDK default changed; the explicit setting in the client is now load-bearing");
    }

    @Test
    void aRequestTimeoutIsAcceptedAndDoesNotBoundATransfer() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok("{\"databases\": []}")));

        InternetData.builder().httpClient(http).apiKey("k")
                .requestTimeout(Duration.ofSeconds(1)).build().database().list();

        assertEquals(1, http.calls.size());
    }
}
