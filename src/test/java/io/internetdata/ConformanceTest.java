package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.internetdata.internal.ApiClient;
import io.internetdata.model.Database;
import io.internetdata.model.DatabaseVersion;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Asserts the shared conformance corpus that every InternetData SDK asserts.
 *
 * <p>The corpus is generated into testdata/ and is identical across languages, so a behavior that
 * drifts here fails here rather than surfacing as two client libraries quietly disagreeing about
 * the same refusal.
 */
class ConformanceTest {
    // The generated models carry java.time.LocalDate, so a bare ObjectMapper cannot read one
    // back. This is the same mapper the client itself deserializes responses with.
    private static final ObjectMapper MAPPER = ApiClient.createDefaultObjectMapper();
    private static JsonNode data;

    @BeforeAll
    static void loadCorpus() throws IOException {
        data = MAPPER.readTree(Path.of("testdata", "testdata.json").toFile());
    }

    /**
     * The fixture set that has caught every SDK so far: a 404 is a CLIENT error, and the two 429s
     * differ only by {@code Retry-After}.
     *
     * <p>Three of the first four VPNDetection SDKs mapped 400/401/403/429 and let everything else
     * fall through to a retryable {@code server_error}, so a misspelled database id was retried
     * twice before failing. Classification is on the RANGE, and this is what pins it.
     */
    @Test
    void everyRefusalIsClassifiedTheWayTheCorpusSaysItIs() throws IOException {
        for (JsonNode c : data.get("errors")) {
            String name = c.get("name").asText();
            Map<String, String> headers = new HashMap<>();
            c.get("headers").fieldNames().forEachRemaining(
                    h -> headers.put(h, c.get("headers").get(h).asText()));
            StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/metadata",
                    new StubHttpClient.Route(c.get("status").asInt(),
                            MAPPER.writeValueAsString(c.get("body")), headers)));

            // No retries, so a retryable error still surfaces rather than looping.
            InternetData client = clientOn(http).retries(0).build();
            JsonNode expect = c.get("expect");
            InternetDataException err = assertThrows(InternetDataException.class,
                    () -> client.database().metadata("bogon_ip_v1"), name);

            assertEquals(ErrorKind.valueOf(expect.get("kind").asText().toUpperCase()),
                    err.kind(), name);
            assertEquals(expect.get("retryable").asBoolean(), err.retryable(), name + ": retryable");
            assertEquals(c.get("status").asInt(), err.statusCode().orElseThrow(), name + ": status");
            if (expect.get("message") != null) {
                // The API's own `rc`, not the status: it is what says WHICH 403 this is.
                assertEquals(expect.get("message").asText(), err.getMessage(), name + ": message");
            }
            if (expect.get("retryAfterSeconds") != null) {
                assertEquals(expect.get("retryAfterSeconds").asLong(),
                        err.retryAfter().orElseThrow().toSeconds(), name);
            } else {
                assertTrue(err.retryAfter().isEmpty(),
                        name + ": a 429 with no Retry-After is a spent allowance, not a rate limit");
            }
            assertEquals(1, http.calls.size(), name + ": a non-retryable failure was retried");
        }
    }

    /** A retryable failure IS retried, so the fixture above is proving a difference, not a default. */
    @Test
    void aRetryableRefusalIsActuallyRetried() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/metadata",
                new StubHttpClient.Route(503, "{\"rc\": \"NOT_AVAILABLE\"}", Map.of())));

        InternetData client = clientOn(http).retries(2).build();
        assertThrows(InternetDataException.class, () -> client.database().metadata("bogon_ip_v1"));

        assertEquals(3, http.calls.size(), "a 503 should have been attempted three times");
    }

    @Test
    void theStandingsAreExactlyTheOnesTheCorpusNames() {
        assertEquals(strings(data.get("standings")),
                Stream.of(Database.StandingEnum.values()).map(Database.StandingEnum::getValue).toList());
    }

    @Test
    void theRedistributionTermsAreExactlyTheOnesTheCorpusNames() {
        assertEquals(strings(data.get("redistribution")),
                Stream.of(Database.RedistributionEnum.values())
                        .map(Database.RedistributionEnum::getValue).toList());
    }

    /**
     * The formats, on both sides of the wire: the enum a caller passes in, and the one the API
     * answers with when it lists what a version is BUILT in.
     */
    @Test
    void theFormatsAreExactlyTheOnesTheCorpusNames() {
        List<String> expected = strings(data.get("formats"));
        assertEquals(expected,
                Stream.of(DatabaseFormat.values()).map(DatabaseFormat::wireValue).toList());
        assertEquals(expected, Stream.of(DatabaseVersion.FormatsEnum.values())
                .map(DatabaseVersion.FormatsEnum::getValue).toList());
    }

    /**
     * The visibility contract, which is a rule about what a CLIENT may do rather than a fixture it
     * can replay.
     *
     * <p>A database built for one customer is ABSENT from another organization's listing rather
     * than shown as unlicensed, so the listing is the server's answer and is not the same for every
     * key. The corpus names the rules by id and deliberately names no private database, because it
     * is committed into public repositories; each id has to reach a handler here, so a rule added
     * to the corpus turns this red rather than passing unnoticed.
     */
    @Test
    void everyVisibilityRuleHasAHandlerAndHolds() {
        Set<String> handled = new LinkedHashSet<>();
        for (JsonNode rule : data.get("visibility").get("clientRules")) {
            String id = rule.asText();
            switch (id) {
                case "listing-is-returned-as-served":
                    listingIsReturnedAsServed();
                    break;
                case "no-catalog-is-compiled-into-the-client":
                    noCatalogIsCompiledIntoTheClient();
                    break;
                case "a-listing-is-never-reused-across-clients":
                    aListingIsNeverReusedAcrossClients();
                    break;
                default:
                    fail("the corpus names a visibility rule this SDK does not handle: " + id);
            }
            handled.add(id);
        }
        assertEquals(strings(data.get("visibility").get("clientRules")), List.copyOf(handled),
                "a handler ran that no rule asked for");
    }

    /** Every family the server sent, in the order it sent them, filtered by nothing. */
    private static void listingIsReturnedAsServed() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok(listing("licensed", "expired", "unlicensed"))));

        List<Database> got = clientOn(http).build().database().list();

        assertEquals(List.of("family_a", "family_b", "family_c"),
                got.stream().map(Database::getBase).toList(),
                "the listing was reordered or filtered on the way through");
        assertEquals(Database.StandingEnum.UNLICENSED, got.get(2).getStanding(),
                "an unlicensed family must survive: standing is the discovery surface");

        // An empty listing stays empty. A client that fell back to a built-in catalog would show a
        // caller databases their organization cannot see.
        StubHttpClient empty = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok("{\"databases\": []}")));
        assertTrue(clientOn(empty).build().database().list().isEmpty(), "the client invented a catalog");
    }

    /**
     * No database id is written into the library at all, so there is no second source a listing
     * could be reconstructed from.
     *
     * <p>Checked against the sources rather than argued: an id looks like {@code <family>_v<n>}, and
     * finding one in a string literal under src/main is what a compiled-in catalog would look like.
     */
    private static void noCatalogIsCompiledIntoTheClient() throws AssertionError {
        Pattern id = Pattern.compile("\"[a-z][a-z0-9]*(?:_[a-z0-9]+)*_v[0-9]+\"");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = id.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    hits.add(file + ": " + m.group());
                }
            }
        } catch (IOException e) {
            throw new AssertionError("reading the library sources", e);
        }
        assertEquals(List.of(), hits, "a database id is compiled into the library");
    }

    /**
     * Nothing is cached, so two keys never share an answer.
     *
     * <p>Two organizations are entitled to different databases and one of them may not be allowed
     * to know the other's exists, so a listing held anywhere shared is a leak rather than a
     * performance win. Asserted by counting requests: a client that answered the second call from
     * the first one's result would show one.
     */
    private static void aListingIsNeverReusedAcrossClients() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok(listing("licensed", "expired", "unlicensed"))));

        InternetData a = clientOn(http).apiKey("key-a").build();
        InternetData b = clientOn(http).apiKey("key-b").build();
        a.database().list();
        b.database().list();
        assertEquals(2, http.calls.size(), "two clients shared one listing");
        assertEquals("Bearer key-a", http.authorizations.get(0));
        assertEquals("Bearer key-b", http.authorizations.get(1));

        a.database().list();
        assertEquals(3, http.calls.size(), "a listing was answered from a cache");
        assertFalse(http.calls.get(2).contains("key"), "the API key was put in the URL");
    }

    private static InternetData.Builder clientOn(StubHttpClient http) {
        return InternetData.builder().httpClient(http).apiKey("k");
    }

    /** Three families, one per standing, so nothing about the shape depends on being licensed. */
    private static String listing(String... standings) {
        StringBuilder json = new StringBuilder("{\"databases\": [");
        String[] bases = {"family_a", "family_b", "family_c"};
        for (int i = 0; i < standings.length; i++) {
            json.append(i == 0 ? "" : ", ")
                    .append("{\"base\": \"").append(bases[i]).append("\"")
                    .append(", \"name\": \"Family ").append(i).append("\"")
                    .append(", \"summary\": \"a family\"")
                    .append(", \"standing\": \"").append(standings[i]).append("\"")
                    .append(", \"redistribution\": ")
                    .append("licensed".equals(standings[i]) ? "\"internal\"" : "null")
                    .append(", \"starts\": null, \"expires\": null")
                    .append(", \"versions\": [{\"id\": \"").append(bases[i]).append("_v1\"")
                    .append(", \"version\": 1, \"summary\": \"v1\"")
                    .append(", \"formats\": [\"csvgz\"]}]}");
        }
        return json.append("]}").toString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
