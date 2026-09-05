package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.internetdata.model.Database;
import io.internetdata.model.DatabaseChecksums;
import io.internetdata.model.DatabaseMetadata;
import io.internetdata.model.Download;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The four read endpoints: what they unwrap, and what they put on the wire. */
class EndpointTest {
    /**
     * Every database response nests its payload one level down, so an unwrap at the wrong depth
     * returns nothing at all against a perfectly healthy API. A sibling SDK shipped exactly that in
     * a checksum method; here the depth is fixed by the generated response type, and pinned anyway.
     */
    @Test
    void everyResponseIsUnwrappedAtTheRightDepth() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v2/database/checksum", StubHttpClient.Route.ok("{\"id\": \"bogon_ip_v1\","
                        + " \"format\": \"csvgz\", \"checksums\": {\"md5\": \"m\", \"sha1\": \"s1\","
                        + " \"sha256\": \"s256\", \"sha512\": \"s512\"}}"),
                // A license is held against the FAMILY, and the ids a transfer takes hang off
                // `versions`. Reading an id from the top level is how a listing comes to answer
                // objects whose every field is empty.
                "api/v2/database/list", StubHttpClient.Route.ok(
                        "{\"databases\": [{\"base\": \"bogon_ip\", \"name\": \"Bogon IP\","
                                + " \"summary\": \"non-routable space\", \"standing\": \"licensed\","
                                + " \"license_type\": \"standard\", \"starts\": null,"
                                + " \"expires\": null, \"versions\": [{\"id\": \"bogon_ip_v1\","
                                + " \"version\": 1, \"summary\": \"v1\","
                                + " \"formats\": [\"csvgz\", \"mmdb\"]}]}]}"),
                "api/v2/database/downloads", StubHttpClient.Route.ok(
                        "{\"downloads\": [{\"dataset_id\": \"bogon_ip_v1\", \"format\": \"csvgz\","
                                + " \"outcome\": \"ok\", \"bytes\": 760, \"http_status\": 302,"
                                + " \"apikey_id\": null, \"client_ip\": null, \"user_agent\": null,"
                                + " \"created\": \"2026-09-04T10:00:00Z\"}]}"),
                "api/v2/database/metadata", StubHttpClient.Route.ok(
                        "{\"id\": \"bogon_ip_v1\", \"updated\": \"2026-09-04\", \"entries\": 42,"
                                + " \"schema\": {}, \"size\": {\"csvgz\": 760, \"mmdb\": 3524}}")));
        InternetData client = client(http);

        DatabaseChecksums sums = client.database().checksums("bogon_ip_v1", DatabaseFormat.CSVGZ);
        assertEquals("s256", sums.getSha256(), "the digest a caller wants must not be null");
        assertEquals("m", sums.getMd5());

        List<Database> listing = client.database().list();
        assertEquals(1, listing.size());
        assertEquals("bogon_ip", listing.get(0).getBase());
        assertEquals(Database.StandingEnum.LICENSED, listing.get(0).getStanding());
        assertEquals("bogon_ip_v1", listing.get(0).getVersions().get(0).getId());
        assertEquals(2, listing.get(0).getVersions().get(0).getFormats().size());

        List<Download> attempts = client.database().downloads();
        assertEquals(1, attempts.size());
        assertEquals(Download.OutcomeEnum.OK, attempts.get(0).getOutcome());
        assertEquals(760L, attempts.get(0).getBytes(), "bytes is int64 on the wire");

        DatabaseMetadata meta = client.database().metadata("bogon_ip_v1");
        assertEquals("bogon_ip_v1", meta.getId());
        assertEquals(42L, meta.getEntries());
        // The size a transfer should be budgeted against, per format.
        assertEquals(760L, meta.getSize().get("csvgz"));
        assertEquals(3524L, meta.getSize().get("mmdb"));
        assertEquals("2026-09-04", meta.getUpdated().toString(), "updated is a date, not a datetime");
    }

    /** A `nullable: true` field is a real absence, not an error, and must not become a default. */
    @Test
    void anUnlicensedFamilyCarriesNoTerm() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok("{\"databases\": [{\"base\": \"hosting_ip\","
                        + " \"name\": \"Hosting IP\", \"summary\": \"s\","
                        + " \"standing\": \"unlicensed\", \"license_type\": null,"
                        + " \"starts\": null, \"expires\": null, \"versions\": []}]}")));

        Database family = client(http).database().list().get(0);

        assertEquals(Database.StandingEnum.UNLICENSED, family.getStanding());
        assertNull(family.getLicenseType(), "no license means no license_type term");
        assertNull(family.getExpires());
        assertTrue(family.getVersions().isEmpty());
    }

    /** The limit is a query parameter, and omitting it must not send an empty one. */
    @Test
    void theDownloadHistoryLimitReachesTheWire() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/downloads",
                StubHttpClient.Route.ok("{\"downloads\": []}")));
        InternetData client = client(http);

        client.database().downloads();
        client.database().downloads(5);

        assertNull(java.net.URI.create(http.calls.get(0)).getQuery(),
                "an omitted limit was sent anyway");
        assertEquals("limit=5", java.net.URI.create(http.calls.get(1)).getQuery());
    }

    /** The key rides the Authorization header as a bearer token, and never the query string. */
    @Test
    void theApiKeyReachesTheWireAsABearerToken() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok("{\"databases\": []}")));

        InternetData.builder().httpClient(http).apiKey("secret-key").build().database().list();

        assertEquals("Bearer secret-key", http.authorizations.get(0));
        assertTrue(http.calls.get(0).startsWith(InternetData.DEFAULT_BASE_URL + "/api/v2/"));
        assertFalseContains(http.calls.get(0), "secret-key");
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertTrue(!haystack.contains(needle), "the API key was put in the URL: " + haystack);
    }

    private static InternetData client(StubHttpClient http) {
        return InternetData.builder().httpClient(http).apiKey("k").build();
    }
}
