package io.internetdata;

import io.internetdata.api.DatabaseV2Api;
import io.internetdata.internal.ApiClient;
import io.internetdata.internal.ApiException;
import io.internetdata.model.Database;
import io.internetdata.model.DatabaseChecksums;
import io.internetdata.model.DatabaseMetadata;
import io.internetdata.model.Download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * The licensed database endpoints, reached through {@link InternetData#database()}.
 *
 * <p>This is the whole of the InternetData API today, so the second level buys nothing on its own.
 * It is here because the sibling VPNDetection library keeps the same seven calls under
 * {@code client.database()}, and someone holding both clients should not have to remember which
 * brand spells the same operation which way.
 *
 * <p>Named {@code DatabaseApi} rather than {@code Database} because {@link Database} is already the
 * wire shape for one database family.
 *
 * <p>Access is granted by CONTRACT rather than self-serve, so a key that authenticates perfectly
 * still answers {@link ErrorKind#FORBIDDEN} for a database its organization does not license.
 */
public final class DatabaseApi {
    // A byte[] cannot be longer than this, so a database past it is read in growing chunks and
    // fails on its own weight rather than on a bad allocation size.
    private static final long MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8L;

    private final DatabaseV2Api api;
    private final HttpClient transfer;
    private final int retries;

    DatabaseApi(ApiClient client, int retries) {
        this.api = new DatabaseV2Api(client);
        // The same HTTP client, reached WITHOUT the request interceptor that carries the API key.
        // See fetchDatabaseFile.
        this.transfer = client.getHttpClient();
        this.retries = retries;
    }

    /**
     * Every database your organization may see, with its license beside it.
     *
     * <p>Returned exactly as served. {@code standing} says whether a database is yours today
     * ({@code licensed}), was ({@code expired}), or has never been bought ({@code unlicensed}) -
     * but a database built for one customer does not appear at all for anyone else, so this listing
     * is not a complete inventory of what InternetData publishes and is not the same for two keys.
     * Nothing here reconstructs it, caches it, or reuses one key's listing for another.
     *
     * <p>A license covers a FAMILY while a transfer names a version, so the ids the other methods
     * take come from {@link Database#getVersions()} rather than from {@link Database#getBase()}.
     */
    public List<Database> list() {
        return Wire.execute(retries, () -> api.listDatabases().getDatabases());
    }

    /**
     * What is inside one database: schema, sample rows, row count and the size of each format.
     *
     * <p>Carries {@code updated} and {@code entries}, so it answers whether today's build is worth
     * fetching without moving any of it. {@link DatabaseMetadata#getSize()} is what a transfer
     * should be budgeted against: the catalog reaches several gigabytes.
     */
    public DatabaseMetadata metadata(String id) {
        Objects.requireNonNull(id, "id");
        return Wire.execute(retries, () -> api.databaseMetadataV2(id));
    }

    /**
     * The digests of one published file, to verify a download.
     *
     * <p>The whole set is returned rather than one digest: which ones a database publishes is the
     * API's choice, not this library's.
     */
    public DatabaseChecksums checksums(String id, DatabaseFormat format) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(format, "format");
        return Wire.execute(retries,
                () -> api.databaseChecksumV2(id, format.wireValue()).getChecksums());
    }

    /** Your organization's recent download attempts, newest first, refusals included. */
    public List<Download> downloads() {
        return Wire.execute(retries, () -> api.listDownloads(null).getDownloads());
    }

    /** The same, capped at {@code limit} attempts. The API clamps anything past 200. */
    public List<Download> downloads(int limit) {
        return Wire.execute(retries, () -> api.listDownloads(limit).getDownloads());
    }

    /**
     * The time-limited URL for one database file.
     *
     * <p>The API answers {@code 302} to object storage, and this returns that link rather than the
     * bytes, so a caller can hand the transfer to something better suited to a file that routinely
     * runs to gigabytes. The URL authorizes itself, so it can be passed to a downloader that holds
     * no API key; a transfer already running is not interrupted when it lapses.
     */
    public String downloadUrl(String id, DatabaseFormat format) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(format, "format");
        return Wire.execute(retries, () -> {
            try {
                api.downloadDatabaseV2WithHttpInfo(id, format.wireValue());
            } catch (ApiException e) {
                // The generated method treats any non-2xx as a failure, so the SUCCESS case for
                // this endpoint arrives as an exception carrying the Location header.
                String location = e.getCode() != 302 || e.getResponseHeaders() == null
                        ? null
                        : e.getResponseHeaders().firstValue("Location").orElse(null);
                if (location == null) {
                    throw e;
                }
                return location;
            }
            throw new InternetDataException(ErrorKind.SERVER_ERROR,
                    "expected a redirect to object storage; the HttpClient must not follow redirects");
        });
    }

    /**
     * Download one database file to {@code destination}, and return the bytes written.
     *
     * <p>The bytes are streamed straight to disk, so nothing beyond one chunk is ever held in
     * memory whatever the database weighs. They land in a neighboring {@code .part} file that is
     * renamed on completion, so a transfer that dies half way leaves no truncated file that reads
     * as a whole database, and no leftover either.
     */
    public long download(String id, DatabaseFormat format, Path destination) {
        Objects.requireNonNull(destination, "destination");
        HttpResponse<InputStream> response = fetchDatabaseFile(id, format);
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            long written;
            try (InputStream body = response.body();
                    OutputStream out = Files.newOutputStream(partial)) {
                written = body.transferTo(out);
            }
            assertWholeTransfer(response, written);
            Files.move(partial, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return written;
        } catch (IOException e) {
            // A failure writing is worth telling apart from one reading: a full disk and a reset
            // socket are different problems, and only one of them is ours.
            throw new InternetDataException(ErrorKind.NETWORK,
                    "the database transfer to " + destination + " failed", e);
        } finally {
            // Reached on the way out of every path, including the length check's own. After a
            // successful move there is nothing left under this name, so the successful case pays a
            // stat call and nothing else.
            deleteQuietly(partial);
        }
    }

    /**
     * Download one database file and hand back its bytes.
     *
     * <p><b>This holds the entire file in memory</b>, and the catalog spans seven orders of
     * magnitude, from a few hundred bytes to several gigabytes. Reach for it at the small end,
     * where the bytes go straight into a parser, and use {@link #download} for anything you have
     * not checked against {@link #metadata(String)}.
     */
    public byte[] downloadBytes(String id, DatabaseFormat format) {
        HttpResponse<InputStream> response = fetchDatabaseFile(id, format);
        try (InputStream body = response.body()) {
            long declared = declaredLength(response);
            // Allocated once from the declared length where there is one: readAllBytes grows by
            // doubling, so on a large database the final grow alone costs twice the file.
            byte[] bytes = declared >= 0 && declared <= MAX_ARRAY_LENGTH
                    ? body.readNBytes((int) declared)
                    : body.readAllBytes();
            assertWholeTransfer(response, bytes.length);
            return bytes;
        } catch (IOException e) {
            throw new InternetDataException(ErrorKind.NETWORK, "the database transfer failed", e);
        }
    }

    /**
     * Follows the {@code 302} as a SECOND, unauthenticated request.
     *
     * <p>The presigned URL carries its own authorization, so forwarding the API key would hand a
     * credential to a host that has no business holding it. The key rides a request interceptor on
     * the generated client, and this request is built by hand rather than going through it.
     *
     * <p>That is deliberate rather than a formality, because the JDK's own rule is subtler than it
     * looks: measured on 25, {@code HttpClient.newHttpClient()} defaults to
     * {@link HttpClient.Redirect#NEVER}, and one set to follow redirects DROPS
     * {@code Authorization} when the redirect crosses origins but FORWARDS it when it does not. So
     * "the JDK strips it" is not something a library can lean on.
     */
    private HttpResponse<InputStream> fetchDatabaseFile(String id, DatabaseFormat format) {
        URI url;
        String location = downloadUrl(id, format);
        try {
            url = new URI(location);
        } catch (URISyntaxException e) {
            throw new InternetDataException(ErrorKind.SERVER_ERROR, "the download link is not a URL", e);
        }
        return Wire.retrying(retries, () -> {
            HttpResponse<InputStream> response;
            try {
                // No request timeout is set. The client's is a bound on an API call, and a database
                // that runs to gigabytes is a different kind of wait.
                response = transfer.send(HttpRequest.newBuilder(url).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new InternetDataException(ErrorKind.NETWORK, "the database transfer failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InternetDataException(ErrorKind.NETWORK, "interrupted during a transfer", e);
            }
            if (response.statusCode() != 200) {
                // Left unread: the status is what separates a lapsed link from a refused one, and
                // nothing bounds the size of an error body.
                closeQuietly(response.body());
                throw new InternetDataException(Wire.kindOf(response.statusCode(), null),
                        "object storage refused the download link with status " + response.statusCode(),
                        response.statusCode(), null, null);
            }
            return response;
        });
    }

    // A transfer that dies mid-body can reach a reader as a plain end of stream - the JDK surfaces
    // it as `IOException: closed`, which names no length and does not tell a short transfer from
    // any other closed stream - so a short read is silent unless what arrived is checked against
    // what was promised.
    private static void assertWholeTransfer(HttpResponse<InputStream> response, long written) {
        long declared = declaredLength(response);
        if (declared >= 0 && declared != written) {
            throw new InternetDataException(ErrorKind.NETWORK,
                    "the transfer ended after " + written + " of " + declared + " bytes",
                    response.statusCode(), null, null);
        }
    }

    /** The declared body length, or -1 when the response does not carry a usable one. */
    private static long declaredLength(HttpResponse<InputStream> response) {
        try {
            return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        } catch (NumberFormatException notALength) {
            return -1L;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing useful to do: the caller is already being told what actually went wrong.
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // Same: the status this response carried is the thing worth reporting.
        }
    }
}
