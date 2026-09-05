package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The three transfer methods, and the 302 they all start from. */
class DownloadTest {
    // A real database is gzip, so the payload here is bytes that are not valid text: a transfer
    // that went through a string anywhere would come out mangled rather than merely wrong.
    private static final byte[] PAYLOAD = {0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xc3, (byte) 0x28,
            0x00, (byte) 0xff, 0x4e, 0x2d};
    private static final String SIGNED_URL =
            "https://s3.internetdata.io/signed/bogon_ip_v1.csv.gz?X-Amz-Expires=900";

    /**
     * The success case for this endpoint arrives as an ApiException, because the generated method
     * treats any non-2xx as a failure and a 302 is what a granted download looks like.
     */
    @Test
    void aDownloadUrlIsReadOffTheRedirectRatherThanFollowed() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v2/database/download", new StubHttpClient.Route(302, "",
                        Map.of("Location", SIGNED_URL))));

        InternetData client = client(http);
        assertEquals(SIGNED_URL, client.database().downloadUrl("bogon_ip_v1", DatabaseFormat.CSVGZ));
        assertEquals(1, http.calls.size(), "the redirect was followed");
    }

    @Test
    void aDeniedDownloadCarriesTheApisOwnCode() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v2/database/download", new StubHttpClient.Route(403,
                        "{\"rc\": \"LICENSE_EXPIRED\"}", Map.of())));

        InternetDataException err = assertThrows(InternetDataException.class,
                () -> client(http).database().downloadUrl("bogon_ip_v1", DatabaseFormat.MMDB));

        assertEquals(ErrorKind.FORBIDDEN, err.kind());
        // `LICENSE_EXPIRED` and `NOT_LICENSED` are both 403 and mean different things to the reader;
        // falling back to the status would lose that.
        assertEquals("LICENSE_EXPIRED", err.getMessage());
        assertFalse(err.retryable());
    }

    @Test
    void aDownloadStreamsToAFileAndAgreesWithTheInMemoryCopy(@TempDir Path dir) throws IOException {
        StubHttpClient http = transferring(PAYLOAD.length);
        Path destination = dir.resolve("bogon_ip_v1.csv.gz");
        InternetData client = client(http);

        long written = client.database().download("bogon_ip_v1", DatabaseFormat.CSVGZ, destination);

        assertEquals(PAYLOAD.length, written);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(destination));
        assertFalse(Files.exists(dir.resolve("bogon_ip_v1.csv.gz.part")),
                "the .part file outlived a successful transfer");
        assertArrayEquals(PAYLOAD, client.database().downloadBytes("bogon_ip_v1", DatabaseFormat.CSVGZ),
                "the in-memory copy is not the file");
    }

    /**
     * The presigned URL authorizes itself, so handing object storage the API key would give a host
     * with no business holding it a credential.
     *
     * <p>The JDK is no help here, measured on 25: {@code HttpClient.newHttpClient()} defaults to
     * {@code Redirect.NEVER}, and one set to follow redirects drops {@code Authorization} across
     * origins but FORWARDS it within one. So the 302 is followed as a second, hand-built request
     * that never goes through the key-carrying interceptor, and this is what proves it.
     */
    @Test
    void theApiKeyNeverReachesObjectStorage(@TempDir Path dir) {
        StubHttpClient http = transferring(PAYLOAD.length);

        client(http).database().download("bogon_ip_v1", DatabaseFormat.CSVGZ, dir.resolve("out.gz"));

        assertEquals(2, http.calls.size(), "the 302 was not followed as a second request");
        assertTrue(http.calls.get(0).contains("api/v2/database/download"));
        assertEquals("Bearer k", http.authorizations.get(0), "the API request carried no key");
        assertEquals(SIGNED_URL, http.calls.get(1));
        assertNull(http.authorizations.get(1), "the API key was sent to object storage");
    }

    /**
     * A transfer that dies mid-body reaches a reader as {@code IOException: closed}, which names no
     * length and does not tell a short transfer from any other closed stream - or, worse, as a
     * plain end of stream. Either way a short read is silent unless what arrived is checked against
     * what was promised.
     */
    @Test
    void aTruncatedTransferFailsLoudlyAndLeavesNoFileBehind(@TempDir Path dir) {
        StubHttpClient http = transferring(PAYLOAD.length * 4);
        Path destination = dir.resolve("bogon_ip_v1.csv.gz");
        InternetData client = client(http);

        InternetDataException err = assertThrows(InternetDataException.class,
                () -> client.database().download("bogon_ip_v1", DatabaseFormat.CSVGZ, destination));

        assertEquals(ErrorKind.NETWORK, err.kind());
        assertEquals("the transfer ended after 10 of 40 bytes", err.getMessage());
        assertFalse(Files.exists(destination), "a short transfer was left under the real name");
        assertFalse(Files.exists(dir.resolve("bogon_ip_v1.csv.gz.part")), "the .part file survived");

        assertThrows(InternetDataException.class,
                () -> client.database().downloadBytes("bogon_ip_v1", DatabaseFormat.CSVGZ),
                "the in-memory path accepted a short body");
    }

    /**
     * The whole transfer path, not just downloadUrl: an unlicensed database must fail before any
     * bytes move, and must not be retried.
     */
    @Test
    void anUnlicensedDatabaseIsRefusedOnceAndNotRetried(@TempDir Path dir) {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v2/database/download", new StubHttpClient.Route(403,
                        "{\"rc\": \"NOT_LICENSED\"}", Map.of())));

        InternetDataException err = assertThrows(InternetDataException.class,
                () -> InternetData.builder().httpClient(http).apiKey("k").retries(3).build().database()
                        .download("hosting_ip_v1", DatabaseFormat.CSVGZ, dir.resolve("out.gz")));

        assertEquals(ErrorKind.FORBIDDEN, err.kind());
        assertEquals("NOT_LICENSED", err.getMessage());
        assertFalse(err.retryable(), "a license refusal is not worth retrying");
        assertEquals(1, http.calls.size(), "a 403 was retried");
        assertFalse(Files.exists(dir.resolve("out.gz")));
    }

    /** A lapsed link is object storage's refusal, not the API's, and still has to say so. */
    @Test
    void aLapsedLinkIsReportedRatherThanWrittenToDisk(@TempDir Path dir) {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v2/database/download", new StubHttpClient.Route(302, "",
                        Map.of("Location", SIGNED_URL)),
                "signed/bogon_ip_v1.csv.gz", new StubHttpClient.Route(403,
                        "<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>", Map.of())));

        InternetDataException err = assertThrows(InternetDataException.class,
                () -> client(http).database().download("bogon_ip_v1", DatabaseFormat.CSVGZ,
                        dir.resolve("out.gz")));

        assertEquals(ErrorKind.FORBIDDEN, err.kind());
        assertEquals(403, err.statusCode().orElseThrow());
        assertFalse(Files.exists(dir.resolve("out.gz")));
        assertFalse(Files.exists(dir.resolve("out.gz.part")));
    }

    /**
     * A caller-supplied client set to follow redirects would download the whole database where a
     * link was asked for, so the impossible answer is reported instead of returned.
     */
    @Test
    void aClientThatFollowedTheRedirectIsCalledOut() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/download",
                StubHttpClient.Route.ok("")));

        InternetDataException err = assertThrows(InternetDataException.class,
                () -> client(http).database().downloadUrl("bogon_ip_v1", DatabaseFormat.CSVGZ));

        assertEquals(ErrorKind.SERVER_ERROR, err.kind());
        assertTrue(err.getMessage().contains("must not follow redirects"));
    }

    /** A granted download: the API redirects, and object storage answers the given length. */
    private static StubHttpClient transferring(int declaredLength) {
        return StubHttpClient.of(Map.of(
                "api/v2/database/download", new StubHttpClient.Route(302, "",
                        Map.of("Location", SIGNED_URL)),
                "signed/bogon_ip_v1.csv.gz", new StubHttpClient.Route(200, PAYLOAD,
                        Map.of("Content-Length", String.valueOf(declaredLength)))));
    }

    private static InternetData client(StubHttpClient http) {
        return InternetData.builder().httpClient(http).apiKey("k").build();
    }
}
