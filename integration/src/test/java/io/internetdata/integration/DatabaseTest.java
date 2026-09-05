package io.internetdata.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.internetdata.DatabaseFormat;
import io.internetdata.ErrorKind;
import io.internetdata.InternetDataException;
import io.internetdata.model.Database;
import io.internetdata.model.DatabaseChecksums;
import io.internetdata.model.DatabaseMetadata;
import io.internetdata.model.Download;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The published library against a real deployment.
 *
 * <p>The transfer is budgeted before it starts. {@code metadata} publishes a size per format, and
 * that size is checked against the ceiling below FIRST, so a mistaken database id can never quietly
 * pull one of the multi-gigabyte databases through CI. The CI organization is licensed only for the
 * smallest published databases, but the ceiling does not depend on that staying true.
 */
class DatabaseTest {
    /**
     * 8 MiB. The licensed databases here are a few hundred bytes to a few kilobytes and the
     * production catalog reaches several gigabytes, so this sits three orders of magnitude above
     * anything expected and three below anything alarming. Tripping it means the suite is pointed
     * somewhere unintended, which is exactly when a transfer must not start.
     */
    private static final long CEILING = 8L * 1024 * 1024;

    @TempDir
    static Path tmp;

    private static Transfer transfer;
    private static List<Database> catalog;

    /** One download, shared by the tests that read it, rather than one transfer each. */
    private record Transfer(String id, DatabaseFormat format, long bytes, Path path,
            DatabaseChecksums checksums) {}

    @BeforeEach
    void requireAKey() {
        Assumptions.assumeTrue(Staging.skipReason() == null, Staging::skipReason);
    }

    /**
     * A license covers a database FAMILY, and the ids a transfer takes hang off {@code versions}.
     *
     * <p>Also the visibility contract from the other side: the listing carries families this
     * organization does NOT license, because {@code standing} is how a customer discovers what else
     * exists. What it must never carry is a database built for somebody else, and that is the
     * server's job - nothing here can prove a negative about a name it is not allowed to know.
     */
    @Test
    void theCatalogAnswersTheFamilyShape() {
        List<Database> families = catalog();

        assertFalse(families.isEmpty(), "the catalog came back empty");
        List<String> licensed = new ArrayList<>();
        List<String> visible = new ArrayList<>();
        for (Database family : families) {
            assertNotNull(family.getBase(), "a family arrived with no base");
            assertNotNull(family.getName(), family.getBase() + " carries no name");
            assertNotNull(family.getSummary(), family.getBase() + " carries no summary");
            assertNotNull(family.getStanding(), family.getBase() + " carries no standing");
            for (var version : family.getVersions()) {
                assertNotNull(version.getId(), family.getBase() + " has a version with no id");
                assertTrue(version.getVersion() > 0, version.getId() + " has no version number");
                assertFalse(version.getFormats().isEmpty(), version.getId() + " carries no formats");
            }
            (family.getStanding() == Database.StandingEnum.LICENSED ? licensed : visible)
                    .add(family.getBase());
            if (family.getStanding() != Database.StandingEnum.LICENSED) {
                assertNull(family.getRedistribution(), family.getBase()
                        + " is not licensed but carries a redistribution term");
            }
        }

        assertFalse(licensed.isEmpty(), "this key licenses nothing, so nothing below can run");
        assertFalse(visible.isEmpty(),
                "every family came back licensed, so the listing is not the whole catalog "
                        + "and standing has nothing to discriminate");
        System.out.println("licensed: " + String.join(", ", licensed));
        System.out.println("visible but unlicensed: " + visible.size() + " families");

        // Everything above is vacuous unless the key reached the wire. The client builds happily
        // without one and then sends no Authorization header at all, which is exactly what an
        // unset CI secret produces, so the suite is where that has to be caught.
        List<RecordingHttpClient.Fact> toApi = Staging.probe().recorder().facts().stream()
                .filter(fact -> fact.host().equals(Staging.HOST)).toList();
        assertFalse(toApi.isEmpty(), "no request reached the staging API");
        toApi.forEach(fact ->
                assertTrue(fact.carriedKey(), "the request to " + fact.path() + " carried no key"));
    }

    /**
     * A family this organization holds no license for, taken from the listing rather than named
     * here, so the assertion keeps working when the license set changes.
     */
    @Test
    void aDatabaseTheOrganizationDoesNotLicenseIsRefusedCleanly() {
        String id = unlicensedId().orElseThrow(
                () -> new AssertionError("every visible family is licensed; nothing to refuse"));
        int before = Staging.probe().recorder().facts().size();

        InternetDataException err = assertThrows(InternetDataException.class,
                () -> Staging.client().database().downloadUrl(id, DatabaseFormat.CSVGZ), id);

        assertEquals(ErrorKind.FORBIDDEN, err.kind());
        assertEquals(403, err.statusCode().orElse(0));
        assertFalse(err.retryable(), "a license refusal is not worth retrying");
        // The API says WHICH refusal this is (`{"rc":"NOT_LICENSED"}`). Falling back to the status
        // means the envelope went unread.
        assertFalse(err.getMessage().startsWith("request failed with status"),
                "the message is the client fallback, so the response body went unread");
        assertEquals("NOT_LICENSED", err.getMessage());
        assertEquals(before + 1, Staging.probe().recorder().facts().size(), "a 4xx must not be retried");
        System.out.println("refused " + id + ": " + err.kind() + " " + err.getMessage());
    }

    @Test
    void downloadStreamsARealDatabaseToDiskIntact() throws IOException {
        Transfer dl = downloaded();

        assertTrue(dl.bytes() > 0, "nothing was transferred");
        assertEquals(Files.size(dl.path()), dl.bytes(), "the file is not the length the method reported");
        assertFalse(Files.exists(Path.of(dl.path() + ".part")),
                "the .part file outlived a successful transfer");

        byte[] bytes = Files.readAllBytes(dl.path());
        if (dl.format() == DatabaseFormat.CSVGZ) {
            assertArrayEquals(new byte[] {0x1f, (byte) 0x8b}, new byte[] {bytes[0], bytes[1]},
                    "the payload is not gzip");
        }
        // Reading a top-level `sha256` returns nothing against a healthy API, so this pins the
        // unwrap depth as well as the bytes.
        assertTrue(dl.checksums().getSha256().matches("[0-9a-f]{64}"), "no sha256 was published");
        assertEquals(dl.checksums().getSha256(), sha256(bytes), "the bytes are not the published file");
    }

    /**
     * The link is credential-free by construction: it points at object storage rather than at the
     * API, and the request that follows it carries no key.
     */
    @Test
    void theDownloadLinkCarriesNoCredentialOfOurs() {
        Transfer dl = downloaded();

        String url = Staging.client().database().downloadUrl(dl.id(), dl.format());
        assertTrue(url.startsWith("https://"), "the download link is not https: " + url);
        assertFalse(url.contains(Staging.HOST),
                "the link points back at the API rather than at object storage");

        List<RecordingHttpClient.Fact> storage = Staging.probe().recorder().facts().stream()
                .filter(fact -> !fact.host().equals(Staging.HOST)).toList();
        assertFalse(storage.isEmpty(), "nothing was fetched from object storage, so no 302 was followed");
        storage.forEach(fact -> assertFalse(fact.carriedKey(), "the API key was sent to object storage"));
    }

    @Test
    void downloadBytesAgreesWithTheStreamedCopy() throws IOException {
        Transfer dl = downloaded();

        byte[] bytes = Staging.client().database().downloadBytes(dl.id(), dl.format());

        assertEquals(dl.bytes(), bytes.length, "the in-memory copy is a different length");
        assertArrayEquals(Files.readAllBytes(dl.path()), bytes, "the in-memory copy is not the file");
        assertEquals(dl.checksums().getSha256(), sha256(bytes));
    }

    /** The history endpoint, including the limit, which the API clamps rather than rejects. */
    @Test
    void theDownloadHistoryAnswersRecentAttempts() {
        List<Download> attempts = Staging.client().database().downloads(5);

        assertTrue(attempts.size() <= 5, "the limit was ignored");
        for (Download attempt : attempts) {
            assertNotNull(attempt.getDatasetId(), "an attempt carries no database id");
            assertNotNull(attempt.getOutcome(), attempt.getDatasetId() + " carries no outcome");
            assertNotNull(attempt.getCreated(), attempt.getDatasetId() + " carries no timestamp");
        }
        // Writing the row is fire and forget on the server, so asserting that a download just made
        // is already listed would be a race rather than a check.
        System.out.println("history: " + attempts.size() + " recent attempts");
    }

    private static List<Database> catalog() {
        if (catalog == null) {
            catalog = Staging.client().database().list();
        }
        return catalog;
    }

    /** The first version id of a visible family this organization does not license. */
    private static Optional<String> unlicensedId() {
        return catalog().stream()
                .filter(f -> f.getStanding() != Database.StandingEnum.LICENSED)
                .filter(f -> !f.getVersions().isEmpty())
                .map(f -> f.getVersions().get(0).getId())
                .findFirst();
    }

    /**
     * Memoized, so the transfer tests share one download rather than pulling it four times.
     *
     * <p>The target is taken from the listing rather than named here, and then budgeted against
     * {@code metadata}: the ceiling is what makes that safe, not the license happening to be small.
     */
    private static Transfer downloaded() {
        if (transfer != null) {
            return transfer;
        }
        Database family = catalog().stream()
                .filter(f -> f.getStanding() == Database.StandingEnum.LICENSED)
                .filter(f -> !f.getVersions().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("this key licenses nothing to download"));
        var version = family.getVersions().get(0);
        DatabaseFormat format = DatabaseFormat.valueOf(
                version.getFormats().get(0).getValue().toUpperCase());
        String id = version.getId();

        DatabaseMetadata meta = Staging.client().database().metadata(id);
        assertEquals(id, meta.getId());
        assertNotNull(meta.getUpdated(), id + " publishes no build date");
        Long size = meta.getSize() == null ? null : meta.getSize().get(format.wireValue());
        assertNotNull(size, id + " publishes no size to check a transfer against");
        assertTrue(size > 0, id + " publishes a size of " + size);
        assertTrue(size <= CEILING, id + " is " + size + " bytes, past the " + CEILING + " ceiling");

        Path path = tmp.resolve(id + "." + format.wireValue());
        long bytes = Staging.client().database().download(id, format, path);
        // Read AFTER the transfer, so a rebuild between the two calls shows up as a digest mismatch
        // rather than passing against a digest of nothing.
        DatabaseChecksums checksums = Staging.client().database().checksums(id, format);
        System.out.println(id + "." + format.wireValue() + ": " + bytes
                + " bytes, metadata says " + size + ", " + meta.getEntries() + " entries");
        transfer = new Transfer(id, format, bytes, path, checksums);
        return transfer;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
