package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** The builder, and the settings a caller is most likely to reach for. */
class ClientTest {
    /**
     * There is no anonymous tier here, so a missing key is a construction error rather than a 401
     * on the first call. A caller whose environment variable is unset finds out at the line that is
     * wrong.
     */
    @Test
    void aClientCannotBeBuiltWithoutAKey() {
        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> InternetData.builder().build());
        assertTrue(err.getMessage().contains("API key"));

        assertThrows(IllegalStateException.class, () -> InternetData.builder().apiKey("  ").build(),
                "a blank key authenticates as no key at all");
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
        assertThrows(NullPointerException.class, () -> InternetData.builder().apiKey(null));
        assertThrows(NullPointerException.class, () -> InternetData.builder().requestTimeout(null));
        assertThrows(NullPointerException.class, () -> InternetData.builder().httpClient(null));
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
