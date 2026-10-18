package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import java.time.Duration;
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
            assertEquals(1, http.calls.size(), name + ": more than one request with retries off");
        }
    }

    /**
     * Every refusal the corpus marks non-retryable is sent ONCE with retries ON.
     *
     * <p>The test above runs with retries off, where one request is guaranteed, so its count cannot
     * tell a client that retries a refusal from one that does not. The unlisted 422 is the case that
     * matters most: three of the first four VPNDetection SDKs let an unlisted 4xx fall through to a
     * retryable {@code server_error}.
     */
    @Test
    void aNonRetryableRefusalIsSentOnceWithRetriesOn() throws IOException {
        int checked = 0;
        for (JsonNode c : data.get("errors")) {
            if (c.get("expect").get("retryable").asBoolean()) {
                continue;
            }
            String name = c.get("name").asText();
            Map<String, String> headers = new HashMap<>();
            c.get("headers").fieldNames().forEachRemaining(
                    h -> headers.put(h, c.get("headers").get(h).asText()));
            StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/metadata",
                    new StubHttpClient.Route(c.get("status").asInt(),
                            MAPPER.writeValueAsString(c.get("body")), headers)));

            InternetData client = clientOn(http).retries(2).build();
            assertThrows(InternetDataException.class,
                    () -> client.database().metadata("bogon_ip_v1"), name);

            assertEquals(1, http.calls.size(), name + ": a non-retryable refusal was retried");
            checked++;
        }
        assertTrue(checked > 0, "the corpus names no non-retryable refusal to check");
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

    /**
     * A {@code Retry-After} past 2^31 - 1 ms is waited out on the backoff, still a rate limit
     * carrying the server's value. Waited as given, 2147484 held a call 24.8 days, and
     * 9223372036854775807 failed it with a raw {@code ArithmeticException}.
     */
    @Test
    void aRetryAfterTooLongToWaitIsWaitedOnTheBackoff() {
        for (String value : List.of("2147484", "9223372036854775807", "Fri, 31 Dec 9999 23:59:59 GMT")) {
            StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/metadata",
                    new StubHttpClient.Route(429, "{\"rc\": \"RATE_LIMITED\"}", Map.of("Retry-After", value))));
            InternetData client = clientOn(http).retries(1).build();

            InternetDataException err = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                    InternetDataException.class, () -> client.database().metadata("bogon_ip_v1")), value);

            assertEquals(2, http.calls.size(), value + ": requests");
            assertEquals(ErrorKind.RATE_LIMITED, err.kind(), value);
            assertTrue(err.retryAfter().orElseThrow().compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0,
                    value + ": the server's value");
        }
    }

    /**
     * A {@code Retry-After} is digits, or one of RFC 9110's three date forms in GMT, and nothing
     * else. {@code Long.parseLong} took a sign, so {@code +1} was a one-second throttle, and only
     * the IMF-fixdate was read, so an RFC 850 or asctime date made the 429 a spent allowance.
     */
    @Test
    void aRetryAfterIsDigitsOrAnHttpDateAndNothingElse() {
        java.time.ZonedDateTime at = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60);
        java.util.function.Function<String, String> format = pattern -> at.format(
                java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US));
        String imf = format.apply("EEE, dd MMM yyyy HH:mm:ss");
        String asctime = format.apply("EEE MMM ppd HH:mm:ss yyyy");
        int next = at.getYear() + 1;
        Map<String, ErrorKind> cases = new java.util.LinkedHashMap<>();
        for (String value : List.of("0", "120", " 7 ", imf + " GMT",
                format.apply("EEEE, dd-MMM-yy HH:mm:ss 'GMT'"), asctime)) {
            cases.put(value, ErrorKind.RATE_LIMITED);
        }
        for (String value : List.of(imf + " +0000", imf + " +0500", imf + " PST", imf + " gmt",
                "Xyz, 01 Jan " + next + " 08:49:37 GMT", "Tue, 31 Nov " + next + " 08:49:37 GMT",
                "Fri, 01 Jan " + next + " 25:00:00 GMT", next + "-01-01T08:49:37Z", "1.5", "+1", "-1",
                "0x10", "1_0", "1e3", "tomorrow")) {
            cases.put(value, ErrorKind.QUOTA_EXCEEDED);
        }
        cases.forEach((value, kind) -> {
            StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/metadata",
                    new StubHttpClient.Route(429, "{\"rc\": \"RATE_LIMITED\"}", Map.of("Retry-After", value))));
            InternetData client = clientOn(http).retries(0).build();
            InternetDataException err = assertThrows(InternetDataException.class,
                    () -> client.database().metadata("bogon_ip_v1"), value);
            assertEquals(kind, err.kind(), "'" + value + "'");
        });

        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/metadata",
                new StubHttpClient.Route(429, "{\"rc\": \"RATE_LIMITED\"}", Map.of("Retry-After", imf + " GMT"))));
        InternetDataException err = assertThrows(InternetDataException.class,
                () -> clientOn(http).retries(0).build().database().metadata("bogon_ip_v1"));
        long wait = err.retryAfter().orElseThrow().toSeconds();
        assertTrue(wait >= 55 && wait <= 60, "a date is the seconds until it, in GMT: " + wait);
    }

    /**
     * A 200 a call cannot read is a retried {@code SERVER_ERROR} carrying the status. The generated
     * layer wrapped a body Jackson could not read as an {@code ApiException} with no status, so an
     * HTML page came back {@code NETWORK}, and an empty or {@code null} body came back null. The
     * generator starts a list member at an empty list, so {@code {}} answered an empty catalog or no
     * downloads until 2.4.5, and an entry without its members, or a null entry, came back as a model
     * of nulls or a null until 2.4.6.
     */
    @Test
    void a200TheCallCannotReadIsARetriedServerError() {
        Map<String, List<String>> cases = new java.util.LinkedHashMap<>();
        cases.put("api/v2/database/list", List.of("<html>gateway</html>", "{\"databases\":", "", "null", "[]", "{}",
                "{\"databases\":null}", "{\"databases\":[{}]}", "{\"databases\":[null]}"));
        cases.put("api/v2/database/metadata", List.of("<html>gateway</html>", "", "null", "{}", "{\"id\":\"bogon_ip_v1\"}"));
        cases.put("api/v2/database/checksum", List.of("<html>gateway</html>", "{}", "{\"checksums\":null}",
                "{\"id\":\"bogon_ip_v1\",\"format\":\"mmdb\",\"checksums\":{}}"));
        cases.put("api/v2/database/downloads", List.of("<html>gateway</html>", "null", "{}", "{\"downloads\":null}",
                "{\"downloads\":[{}]}", "{\"downloads\":[null]}"));
        cases.forEach((path, bodies) -> bodies.forEach(body -> {
            StubHttpClient http = StubHttpClient.of(Map.of(path, StubHttpClient.Route.ok(body)));
            InternetData client = clientOn(http).retries(1).build();
            InternetDataException err = assertThrows(InternetDataException.class, () -> call(client, path),
                    path + " '" + body + "'");
            assertEquals(ErrorKind.SERVER_ERROR, err.kind(), path + " '" + body + "'");
            assertEquals(200, err.statusCode().orElseThrow(), path + " '" + body + "'");
            assertEquals(2, http.calls.size(), path + " '" + body + "': retried");
        }));
    }

    private static final Map<String, String> WHOLE = Map.of(
            "api/v2/database/list", "{\"databases\": [{\"base\": \"bogon_ip\", \"name\": \"Bogon IP\","
                    + " \"summary\": \"non-routable space\", \"standing\": \"licensed\", \"open\": true,"
                    + " \"license_type\": \"standard\", \"starts\": null, \"expires\": null, \"renews_at\": null,"
                    + " \"notice_due_at\": null, \"versions\": [{\"id\": \"bogon_ip_v1\", \"version\": 1,"
                    + " \"summary\": \"v1\", \"formats\": [\"csvgz\", \"mmdb\"]}]}]}",
            "api/v2/database/metadata", "{\"id\": \"bogon_ip_v1\", \"updated\": \"2026-09-04\", \"entries\": 42,"
                    + " \"schema\": {\"csvgz\": [{\"name\": \"ip\", \"type\": \"string\", \"description\": \"the range\"}]},"
                    + " \"size\": {\"csvgz\": 760, \"mmdb\": 3524}}",
            "api/v2/database/checksum", "{\"id\": \"bogon_ip_v1\", \"format\": \"mmdb\", \"checksums\": {\"md5\": \"m\","
                    + " \"sha1\": \"s1\", \"sha256\": \"s256\", \"sha512\": \"s512\"}}",
            "api/v2/database/downloads", "{\"downloads\": [{\"dataset_id\": \"bogon_ip_v1\", \"format\": \"csvgz\","
                    + " \"outcome\": \"ok\", \"sample\": true, \"open\": true, \"bytes\": 760, \"http_status\": 302,"
                    + " \"apikey_id\": null, \"client_ip\": null, \"user_agent\": null,"
                    + " \"created\": \"2026-09-04T10:00:00Z\"}]}");

    /**
     * One member per case, left out and then sent as null, from an otherwise whole answer: a body
     * missing several at once lets a decoder that defaults all but one of them pass.
     */
    @Test
    void anAnswerWithoutAMemberTheSpecRequiresIsARetriedServerError() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, StubHttpClient.Route> routes = new HashMap<>();
        WHOLE.forEach((path, body) -> routes.put(path, StubHttpClient.Route.ok(body)));
        InternetData decodes = clientOn(StubHttpClient.of(routes)).build();
        for (String path : WHOLE.keySet()) {
            call(decodes, path);
        }
        List<List<String>> cases = List.of(
                List.of("api/v2/database/list", "/databases/0", "base", "name", "summary", "standing", "open", "versions"),
                List.of("api/v2/database/list", "/databases/0/versions/0", "id", "version", "summary", "formats"),
                List.of("api/v2/database/metadata", "", "id", "updated", "entries", "schema", "size"),
                List.of("api/v2/database/metadata", "/schema/csvgz/0", "name", "type"),
                List.of("api/v2/database/checksum", "/checksums", "md5", "sha1", "sha256", "sha512"),
                List.of("api/v2/database/downloads", "/downloads/0", "dataset_id", "format", "outcome", "sample", "open",
                        "created"));
        for (List<String> each : cases) {
            String path = each.get(0);
            for (String member : each.subList(2, each.size())) {
                for (boolean drop : new boolean[] {true, false}) {
                    JsonNode body = mapper.readTree(WHOLE.get(path));
                    var holder = (com.fasterxml.jackson.databind.node.ObjectNode) body.at(each.get(1));
                    if (drop) {
                        assertTrue(holder.remove(member) != null, member + " is not at " + each.get(1));
                    } else {
                        holder.putNull(member);
                    }
                    String label = path + " " + each.get(1) + " " + member + (drop ? " left out" : " null");
                    StubHttpClient http = StubHttpClient.of(Map.of(path,
                            StubHttpClient.Route.ok(mapper.writeValueAsString(body))));
                    InternetData client = clientOn(http).retries(1).build();
                    InternetDataException err = assertThrows(InternetDataException.class, () -> call(client, path), label);
                    assertEquals(ErrorKind.SERVER_ERROR, err.kind(), label);
                    assertEquals(200, err.statusCode().orElseThrow(), label);
                    assertEquals(2, http.calls.size(), label + ": retried");
                }
            }
        }
    }

    /**
     * A member of the wrong type, one per case, in an otherwise whole answer. Jackson's defaults read
     * {@code "3"} as the number 3, {@code 3} as the string "3" and {@code "true"} as true, so each of
     * these was answered through 2.5.0.
     */
    @Test
    void aMemberOfTheWrongTypeIsARetriedServerError() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<List<Object>> cases = List.of(
                List.of("api/v2/database/list", "/databases/0/versions/0", "version", "3"),
                List.of("api/v2/database/list", "/databases/0/versions/0", "version", 3.5),
                List.of("api/v2/database/list", "/databases/0", "base", 3),
                List.of("api/v2/database/list", "/databases/0", "open", "yes"),
                List.of("api/v2/database/list", "/databases/0", "open", "true"),
                List.of("api/v2/database/list", "/databases/0", "open", 1),
                List.of("api/v2/database/list", "/databases/0/versions/0/formats", "0", 1),
                List.of("api/v2/database/metadata", "", "entries", "42"),
                List.of("api/v2/database/metadata", "/size", "csvgz", "760"),
                List.of("api/v2/database/metadata", "/schema/csvgz/0", "type", false),
                List.of("api/v2/database/checksum", "/checksums", "md5", 5),
                List.of("api/v2/database/downloads", "/downloads/0", "bytes", "1234"),
                List.of("api/v2/database/downloads", "/downloads/0", "sample", "true"));
        for (List<Object> each : cases) {
            String path = (String) each.get(0);
            JsonNode body = mapper.readTree(WHOLE.get(path));
            JsonNode holder = body.at((String) each.get(1));
            JsonNode value = mapper.valueToTree(each.get(3));
            if (holder.isArray()) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) holder).set(Integer.parseInt((String) each.get(2)), value);
            } else {
                ((com.fasterxml.jackson.databind.node.ObjectNode) holder).set((String) each.get(2), value);
            }
            String label = path + " " + each.get(1) + " " + each.get(2) + " = " + value;
            StubHttpClient http = StubHttpClient.of(Map.of(path, StubHttpClient.Route.ok(mapper.writeValueAsString(body))));
            InternetData client = clientOn(http).retries(1).build();
            InternetDataException err = assertThrows(InternetDataException.class, () -> call(client, path), label);
            assertEquals(ErrorKind.SERVER_ERROR, err.kind(), label);
            assertEquals(200, err.statusCode().orElseThrow(), label);
            assertEquals(2, http.calls.size(), label + ": retried");
        }
    }

    /**
     * The table is the spec's {@code required} lists less what each marks nullable, which the
     * generator writes as the models' {@code @Nonnull} fields, so a re-pin that adds one turns this
     * red until the table has it. Every model an answer reaches must be in the table.
     */
    @Test
    void theRequiredMembersAreTheModelsNonnullOnes() throws ReflectiveOperationException {
        Set<Class<?>> envelopes = Set.of(io.internetdata.model.DatabaseList.class,
                io.internetdata.model.DownloadList.class, io.internetdata.model.DatabaseChecksumsResponse.class);
        Set<Class<?>> reached = new LinkedHashSet<>();
        List<Class<?>> pending = new ArrayList<>(envelopes);
        pending.add(io.internetdata.model.DatabaseMetadata.class);
        while (!pending.isEmpty()) {
            Class<?> model = pending.remove(pending.size() - 1);
            if (!reached.add(model)) {
                continue;
            }
            Set<String> nonnull = new java.util.TreeSet<>();
            for (java.lang.reflect.Field constant : model.getFields()) {
                if (!constant.getName().startsWith("JSON_PROPERTY_")) {
                    continue;
                }
                String member = (String) constant.get(null);
                java.lang.reflect.Field field = model.getDeclaredField(camel(member));
                if (field.isAnnotationPresent(javax.annotation.Nonnull.class)) {
                    nonnull.add(member);
                }
                models(field.getGenericType(), pending);
            }
            List<String> table = RequiredMembers.MEMBERS.get(model);
            assertTrue(table != null, model.getSimpleName() + " is not in the table");
            if (envelopes.contains(model)) {
                assertTrue(nonnull.containsAll(table), model.getSimpleName());
            } else {
                assertEquals(nonnull, new java.util.TreeSet<>(table), model.getSimpleName());
            }
        }
        assertEquals(RequiredMembers.MEMBERS.keySet(), reached);
    }

    private static String camel(String member) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : member.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static void models(java.lang.reflect.Type type, List<Class<?>> into) {
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                models(argument, into);
            }
        } else if (type instanceof Class<?> model && !model.isEnum()
                && model.getPackageName().equals("io.internetdata.model")) {
            into.add(model);
        }
    }

    private static void call(InternetData client, String path) {
        switch (path) {
            case "api/v2/database/list" -> client.database().list();
            case "api/v2/database/metadata" -> client.database().metadata("bogon_ip_v1");
            case "api/v2/database/checksum" -> client.database().checksums("bogon_ip_v1", DatabaseFormat.MMDB);
            default -> client.database().downloads();
        }
    }

    @Test
    void theStandingsAreExactlyTheOnesTheCorpusNames() {
        assertEquals(strings(data.get("standings")),
                Stream.of(io.internetdata.model.Standing.values()).map(io.internetdata.model.Standing::getValue).toList());
    }

    @Test
    void theLicenseTypesAreExactlyTheOnesTheCorpusNames() {
        assertEquals(strings(data.get("license_type")),
                Stream.of(Database.LicenseTypeEnum.values())
                        .map(Database.LicenseTypeEnum::getValue).toList());
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
        assertEquals(expected, Stream.of(io.internetdata.model.DatabaseFormat.values())
                .map(io.internetdata.model.DatabaseFormat::getValue).toList());
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
        assertEquals(io.internetdata.model.Standing.UNLICENSED, got.get(2).getStanding(),
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
                    .append(", \"open\": false")
                    .append(", \"license_type\": ")
                    .append("licensed".equals(standings[i]) ? "\"standard\"" : "null")
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
