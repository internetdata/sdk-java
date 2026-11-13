package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.internetdata.internal.ApiClient;
import io.internetdata.model.AsnLookupResponse;
import io.internetdata.model.Database;
import io.internetdata.model.DatabaseVersion;
import io.internetdata.model.DomainLookupResponse;
import io.internetdata.model.LookupResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Asserts the shared conformance corpus that every InternetData SDK asserts.
 *
 * <p>The corpus is generated into testdata/ and is identical across languages, so a behavior that
 * drifts here fails here rather than surfacing as two client libraries quietly disagreeing about
 * the same address or the same refusal.
 */
class ConformanceTest {
    // The generated models carry java.time.LocalDate, so a bare ObjectMapper cannot read one
    // back. This is the same mapper the client itself deserializes responses with.
    private static final ObjectMapper MAPPER = ApiClient.createDefaultObjectMapper();
    // And as the client configures it, so a model read here is the one the client would read.
    private static final ObjectMapper READER = RequiredMembers.configure(ApiClient.createDefaultObjectMapper());
    private static JsonNode data;

    /**
     * The two calls every refusal is run against, by the path each asks: a lookup, whose refusal
     * carries its message in {@code error}, and a database call, whose carries it in {@code rc}.
     */
    private static final Map<String, Consumer<InternetData>> REFUSED = new LinkedHashMap<>();

    static {
        REFUSED.put("1.1.1.1", client -> client.lookup("1.1.1.1"));
        REFUSED.put("api/v2/database/metadata", client -> client.database().metadata("bogon_ip_v1"));
    }

    @BeforeAll
    static void loadCorpus() throws IOException {
        data = MAPPER.readTree(Path.of("testdata", "testdata.json").toFile());
    }

    @Test
    void isBogonMatchesTheCanonicalRanges() {
        for (JsonNode c : data.get("isBogon")) {
            String ip = c.get("ip").asText();
            assertEquals(c.get("expect").asBoolean(), Bogon.isBogon(ip),
                    ip + " (" + c.get("why").asText() + ")");
        }
    }

    // A server listening on :: sees every IPv4 visitor as ::ffff:a.b.c.d. Read whole that is inside
    // ::ffff:0:0/96, so an SDK that did not unmap answered each one locally as a bogon.
    @Test
    void anIpv4MappedAddressIsTheIpv4AddressItCarries() {
        for (JsonNode c : data.get("ipv4Mapped")) {
            String ip = c.get("ip").asText();
            String carries = c.get("carries").asText();
            boolean expect = c.get("expect").asBoolean();
            assertEquals(expect, Bogon.isBogon(ip), ip + ": isBogon (" + c.get("why").asText() + ")");
            Map<String, StubHttpClient.Route> routes = Map.of(carries,
                    StubHttpClient.Route.ok(StubHttpClient.answer(carries)));

            StubHttpClient http = StubHttpClient.of(routes);
            try (InternetData client = clientOn(http).build()) {
                Result r = client.lookup(ip);
                assertEquals(carries, r.ip(), ip + ": the answer names the address it carries");
                assertEquals(expect, r.isBogon(), ip + ": answered locally exactly when a bogon");
                if (expect) {
                    assertEquals(0, http.calls.size(), ip + ": a bogon must not reach the network");
                } else {
                    assertEquals(1, http.calls.size(), ip + ": one request");
                    assertTrue(http.calls.get(0).endsWith("/" + carries), ip + ": sent as " + carries);
                    client.lookup(carries);
                    assertEquals(1, http.calls.size(), ip + ": " + carries + " is then a cache hit");
                }
            }

            // The mapped form alone: asked beside its plain form, a batch that sent the address as
            // given would still have been answered for the plain one.
            StubHttpClient batch = StubHttpClient.of(routes);
            try (InternetData client = clientOn(batch).retries(0).build()) {
                LinkedHashMap<String, BatchResult> got = client.lookupBatch(List.of(ip));
                assertEquals(List.of(ip), new ArrayList<>(got.keySet()), ip + ": keyed as passed");
                assertEquals(carries, got.get(ip).orElseThrow().ip(), ip + ": batch answer");
                assertEquals(expect ? List.of() : List.of(carries), batch.batchIps,
                        ip + ": the batch body");
            }
        }
    }

    // Two spellings of one address are one address: sent once, and answered under each spelling.
    @Test
    void aMappedAddressBesideItsPlainFormIsSentOnce() {
        for (boolean cached : new boolean[] {true, false}) {
            StubHttpClient http = StubHttpClient.echoing(Duration.ZERO);
            try (InternetData client = clientOn(http).cacheEnabled(cached).build()) {
                LinkedHashMap<String, BatchResult> got = client.lookupBatch(
                        List.of("8.8.8.8", "::ffff:8.8.8.8", "::FFFF:808:808"));

                assertEquals(List.of("8.8.8.8", "::ffff:8.8.8.8", "::FFFF:808:808"), new ArrayList<>(got.keySet()));
                got.values().forEach(answer -> assertEquals("8.8.8.8", answer.orElseThrow().ip()));
                assertEquals(List.of("8.8.8.8"), http.batchIps, "cache " + cached + ": the batch body");
            }
        }
    }

    /**
     * A bogon is answered locally in the corpus's answer, the address filled in: every flag present
     * and false, every detail object present and empty, and every shared object with every key.
     */
    @Test
    void aBogonIsAnsweredLocallyInTheShapeTheCorpusNames() throws IOException {
        JsonNode bogon = data.get("bogonResponse");
        StubHttpClient http = StubHttpClient.of(Map.of());
        try (InternetData client = clientOn(http).build()) {
            Result r = client.lookup("10.0.0.1");

            assertTrue(r.isBogon());
            assertEquals("10.0.0.1", r.ip());
            ObjectNode answer = bogon.get("answer").deepCopy();
            answer.put("ip", "10.0.0.1");
            assertEquals(READER.treeToValue(answer, LookupResponse.class), r.raw(), "the corpus's answer");
            for (JsonNode flag : bogon.get("flagsFalse")) {
                assertEquals(Optional.of(false), member(r, flag.asText()),
                        flag.asText() + " must be present and false");
            }
            for (String list : List.of("emptyObjects", "emptyLists")) {
                for (JsonNode object : bogon.get(list)) {
                    Optional<?> value = member(r, object.asText());
                    assertTrue(value.isPresent(), object.asText() + " must be present");
                    assertTrue(isEmpty(value.get()), object.asText() + " must be empty");
                }
            }
            for (String object : List.of("location", "asn", "company", "abuse", "whois", "rwhois",
                    "hosted_domains")) {
                Object value = member(r, object).orElseThrow();
                String key = object.equals("hosted_domains") ? "hostedDomains" : object;
                assertEquals(READER.treeToValue(bogon.get(key), value.getClass()), value,
                        object + " must carry every key, empty");
            }
            assertEquals(Optional.of(bogon.get("hostname").asText()), member(r, "hostname"));
            assertTrue(r.trialExpired().isEmpty(), "a bogon carries no trial");
            assertEquals(0, http.calls.size(), "a bogon must not reach the network");
        }
    }

    /**
     * Each plan's answer, for an address, an AS and a domain: what is present carries its value, a
     * member the plan leaves out reads as empty and never as false, zero or an empty list, and so
     * does one inside a shared object, which the generated model holds as null.
     */
    @Test
    void eachLookupKeepsAbsentApartFromFalseAcrossEveryPlanShape() throws IOException {
        for (JsonNode c : data.get("lookup")) {
            String name = c.get("name").asText();
            String call = c.get("call").asText();
            String target = c.get("target").asText();
            StubHttpClient http = StubHttpClient.of(Map.of(path(call, target), new StubHttpClient.Route(
                    c.get("status").asInt(), MAPPER.writeValueAsString(c.get("body")), Map.of())));

            try (InternetData client = clientOn(http).build()) {
                Object answer = lookUp(client, call, target);
                JsonNode expect = c.get("expect");
                assertEquals(1, http.calls.size(), name + ": one request");

                for (Map.Entry<String, JsonNode> present : expect.get("present").properties()) {
                    Optional<?> value = member(answer, present.getKey());
                    assertTrue(value.isPresent(), name + ": " + present.getKey() + " should be present");
                    assertEquals(normal(present.getValue()), wire(value.get()), name + ": " + present.getKey());
                }
                List<String> empty = strings(expect.get("empty"));
                for (JsonNode object : expect.get("objects")) {
                    Optional<?> value = member(answer, object.asText());
                    assertTrue(value.isPresent(), name + ": " + object.asText() + " should be present");
                    boolean shouldBe = empty.contains(object.asText());
                    assertEquals(shouldBe, isEmpty(value.get()),
                            name + ": " + object.asText() + (shouldBe ? " should" : " should not") + " be empty");
                }
                for (JsonNode absent : expect.get("absent")) {
                    assertTrue(member(answer, absent.asText()).isEmpty(),
                            name + ": " + absent.asText() + " must be ABSENT, not false");
                }
                if (expect.has("absentIn")) {
                    for (Map.Entry<String, JsonNode> within : expect.get("absentIn").properties()) {
                        JsonNode object = MAPPER.valueToTree(member(answer, within.getKey()).orElseThrow());
                        for (JsonNode absent : within.getValue()) {
                            assertFalse(object.has(absent.asText()), name + ": " + within.getKey() + "."
                                    + absent.asText() + " must be ABSENT, not " + object.get(absent.asText()));
                        }
                    }
                }
            }
        }
    }

    /** Every member each answer's model carries is reachable from the answer type a caller holds. */
    @Test
    void everyMemberOfEachAnswerHasAnAccessor() throws ReflectiveOperationException {
        Map<Class<?>, Object> answers = Map.of(
                LookupResponse.class, Result.bogon("10.0.0.1"),
                AsnLookupResponse.class, AsnResult.of(new AsnLookupResponse()),
                DomainLookupResponse.class, DomainResult.of(new DomainLookupResponse()));
        for (Map.Entry<Class<?>, Object> answer : answers.entrySet()) {
            for (java.lang.reflect.Field constant : answer.getKey().getFields()) {
                if (constant.getName().startsWith("JSON_PROPERTY_")) {
                    member(answer.getValue(), (String) constant.get(null));
                }
            }
        }
    }

    /**
     * The fixture set that has caught every SDK so far: a 404 is a CLIENT error, and the two 429s
     * differ only by {@code Retry-After}.
     *
     * <p>Three of the first four VPNDetection SDKs mapped 400/401/403/429 and let everything else
     * fall through to a retryable {@code server_error}, so a misspelled database ID was retried
     * twice before failing. Classification is on the RANGE, and this is what pins it.
     */
    @Test
    void everyRefusalIsClassifiedTheWayTheCorpusSaysItIs() throws IOException {
        for (JsonNode c : data.get("errors")) {
            for (Map.Entry<String, Consumer<InternetData>> call : REFUSED.entrySet()) {
                String name = c.get("name").asText() + " on " + call.getKey();
                Map<String, String> headers = new HashMap<>();
                c.get("headers").fieldNames().forEachRemaining(
                        h -> headers.put(h, c.get("headers").get(h).asText()));
                StubHttpClient http = StubHttpClient.of(Map.of(call.getKey(),
                        new StubHttpClient.Route(c.get("status").asInt(),
                                MAPPER.writeValueAsString(c.get("body")), headers)));

                // No retries, so a retryable error still surfaces rather than looping.
                InternetData client = clientOn(http).retries(0).build();
                JsonNode expect = c.get("expect");
                InternetDataException err = assertThrows(InternetDataException.class,
                        () -> call.getValue().accept(client), name);

                assertEquals(ErrorKind.valueOf(expect.get("kind").asText().toUpperCase()),
                        err.kind(), name);
                assertEquals(expect.get("retryable").asBoolean(), err.retryable(), name + ": retryable");
                assertEquals(c.get("status").asInt(), err.statusCode().orElseThrow(), name + ": status");
                if (expect.get("message") != null) {
                    // The API's own `error` or `rc`, not the status: it is what says WHICH 403 this is.
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
            for (Map.Entry<String, Consumer<InternetData>> call : REFUSED.entrySet()) {
                String name = c.get("name").asText() + " on " + call.getKey();
                Map<String, String> headers = new HashMap<>();
                c.get("headers").fieldNames().forEachRemaining(
                        h -> headers.put(h, c.get("headers").get(h).asText()));
                StubHttpClient http = StubHttpClient.of(Map.of(call.getKey(),
                        new StubHttpClient.Route(c.get("status").asInt(),
                                MAPPER.writeValueAsString(c.get("body")), headers)));

                InternetData client = clientOn(http).retries(2).build();
                assertThrows(InternetDataException.class, () -> call.getValue().accept(client), name);

                assertEquals(1, http.calls.size(), name + ": a non-retryable refusal was retried");
                checked++;
            }
        }
        assertTrue(checked > 0, "the corpus names no non-retryable refusal to check");
    }

    /** A retryable failure IS retried, so the fixture above is proving a difference, not a default. */
    @Test
    void aRetryableRefusalIsActuallyRetried() {
        for (Map.Entry<String, Consumer<InternetData>> call : REFUSED.entrySet()) {
            StubHttpClient http = StubHttpClient.of(Map.of(call.getKey(),
                    new StubHttpClient.Route(503, "{\"rc\": \"NOT_AVAILABLE\"}", Map.of())));

            InternetData client = clientOn(http).retries(2).build();
            assertThrows(InternetDataException.class, () -> call.getValue().accept(client));

            assertEquals(3, http.calls.size(), call.getKey() + ": a 503 should have been attempted three times");
        }
    }

    /**
     * A {@code Retry-After} past 2^31 - 1 ms is waited out on the backoff, still a rate limit
     * carrying the server's value. Waited as given, 2147484 held a call 24.8 days, and
     * 9223372036854775807 failed it with a raw {@code ArithmeticException}.
     */
    @Test
    void aRetryAfterTooLongToWaitIsWaitedOnTheBackoff() {
        for (String value : List.of("2147484", "9223372036854775807", "Fri, 31 Dec 9999 23:59:59 GMT")) {
            for (Map.Entry<String, Consumer<InternetData>> call : REFUSED.entrySet()) {
                StubHttpClient http = StubHttpClient.of(Map.of(call.getKey(), new StubHttpClient.Route(429,
                        "{\"error\": \"too many requests\", \"rc\": \"RATE_LIMITED\"}",
                        Map.of("Retry-After", value))));
                InternetData client = clientOn(http).retries(1).build();
                String what = value + " on " + call.getKey();

                InternetDataException err = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                        InternetDataException.class, () -> call.getValue().accept(client)), what);

                assertEquals(2, http.calls.size(), what + ": requests");
                assertEquals(ErrorKind.RATE_LIMITED, err.kind(), what);
                assertTrue(err.retryAfter().orElseThrow().compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0,
                        what + ": the server's value");
            }
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
        for (Map.Entry<String, Consumer<InternetData>> call : REFUSED.entrySet()) {
            cases.forEach((value, kind) -> {
                StubHttpClient http = StubHttpClient.of(Map.of(call.getKey(),
                        new StubHttpClient.Route(429, "{\"rc\": \"RATE_LIMITED\"}", Map.of("Retry-After", value))));
                InternetData client = clientOn(http).retries(0).build();
                InternetDataException err = assertThrows(InternetDataException.class,
                        () -> call.getValue().accept(client), value);
                assertEquals(kind, err.kind(), "'" + value + "' on " + call.getKey());
            });
        }

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
        for (String path : List.of("1.1.1.1", "myip", "api/v1/entitlement", "asn/9009", "domain/example.com")) {
            cases.put(path, List.of("<html>gateway</html>", "{\"ip\": \"1.1.1.1\", \"is_bo", "", "null", "[]", "{}"));
        }
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

    private static final String ENTITLEMENT = "{\"org_id\": \"6f1c2a52-6c83-4c8a-9a3e-2f3b8c1d0e9f\","
            + " \"apikey\": {\"id\": \"0b6e2d8a-1c4f-4a5e-8d7b-3e2f1a0c9b8d\", \"expires\": null,"
            + " \"allowed_cidrs\": []}, \"plan\": {\"key\": \"scale\", \"tier\": \"scale\"},"
            + " \"usage\": {\"requests\": 12, \"quota\": 100000, \"hard_limit\": null,"
            + " \"window_start\": \"2026-10-01T00:00:00Z\", \"window_end\": \"2026-11-01T00:00:00Z\"}}";

    /** Every path below with an answer to it, the database calls' written here and a lookup's the corpus's. */
    private static final List<String> PATHS = List.of("1.1.1.1", "myip", "api/v1/entitlement", "asn/9009",
            "domain/example.com", "api/v2/database/list", "api/v2/database/metadata", "api/v2/database/checksum",
            "api/v2/database/downloads");

    // An answer each call reads whole, which a case takes one thing away from or adds one to.
    private static String whole(String path) {
        return switch (path) {
            case "1.1.1.1", "myip" -> lookupBody("paid-services");
            case "asn/9009" -> lookupBody("asn-paid-answer");
            case "domain/example.com" -> lookupBody("domain-paid-answer");
            case "api/v1/entitlement" -> ENTITLEMENT;
            default -> WHOLE.get(path);
        };
    }

    /**
     * One member per case, left out and then sent as null, from an otherwise whole answer: a body
     * missing several at once lets a decoder that defaults all but one of them pass.
     */
    @Test
    void anAnswerWithoutAMemberTheSpecRequiresIsARetriedServerError() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, StubHttpClient.Route> routes = new HashMap<>();
        PATHS.forEach(path -> routes.put(path, StubHttpClient.Route.ok(whole(path))));
        InternetData decodes = clientOn(StubHttpClient.of(routes)).build();
        for (String path : PATHS) {
            call(decodes, path);
        }
        List<List<String>> cases = List.of(
                List.of("1.1.1.1", "", "ip", "is_bogon", "is_tor", "is_relay", "is_anycast", "is_carrier",
                        "is_satellite", "is_ssh", "is_smtp", "is_web", "is_dns_resolver", "is_bittorrent", "tor",
                        "relay", "location", "asn", "company", "hostname", "abuse"),
                List.of("myip", "", "ip", "is_bogon", "location"),
                List.of("1.1.1.1", "/location", "continent", "continent_name", "country", "country_name", "timezone",
                        "currency_code", "currency_name", "currency_symbol", "flag_emoji", "flag_unicode"),
                List.of("1.1.1.1", "/asn", "asn", "name", "domain", "type", "prefix"),
                List.of("1.1.1.1", "/company", "name", "domain"),
                List.of("1.1.1.1", "/abuse", "email"),
                List.of("1.1.1.1", "/whois", "id", "name", "org", "country", "status", "source", "domain",
                        "abuse_email", "asn"),
                List.of("1.1.1.1", "/rwhois", "id", "name", "country", "source", "domain", "abuse_email"),
                List.of("1.1.1.1", "/hosted_domains", "total"),
                List.of("1.1.1.1", "/web/0", "port", "tls_ok", "session_ok", "status_code", "server", "location",
                        "title", "cert_subject_cn", "cert_subject_o", "cert_issuer_dn", "cert_browser_trusted",
                        "tls_version", "first_seen", "last_seen"),
                List.of("api/v1/entitlement", "", "org_id", "apikey", "plan", "usage"),
                List.of("api/v1/entitlement", "/apikey", "id", "allowed_cidrs"),
                List.of("api/v1/entitlement", "/plan", "key", "tier"),
                List.of("api/v1/entitlement", "/usage", "requests", "quota", "window_start", "window_end"),
                List.of("asn/9009", "", "asn", "name", "country", "registry", "allocated", "domain"),
                List.of("asn/9009", "/prefixes/0", "prefix", "peer_count", "rpki_status", "first_seen", "country",
                        "size", "other_origins"),
                List.of("domain/example.com", "", "domain", "is_registered", "has_a", "has_aaaa", "has_mx", "has_ns",
                        "has_cname"),
                List.of("domain/example.com", "/dns", "a", "aaaa", "mx", "ns", "cname"),
                List.of("domain/example.com", "/dns/a/0", "ip", "last_seen"),
                List.of("domain/example.com", "/dns/mx/0", "priority", "host", "last_seen"),
                List.of("domain/example.com", "/dns/ns/0", "host", "last_seen"),
                List.of("domain/example.com", "/ip_ranges", "domain", "redirects_to", "total", "ipv4", "ranges"),
                List.of("domain/example.com", "/ip_ranges/ranges/0", "range", "name", "country"),
                List.of("api/v2/database/list", "/databases/0", "base", "name", "summary", "standing", "open", "versions"),
                List.of("api/v2/database/list", "/databases/0/versions/0", "id", "version", "summary", "formats"),
                List.of("api/v2/database/metadata", "", "id", "updated", "entries", "schema", "size"),
                List.of("api/v2/database/metadata", "/schema/csvgz/0", "name", "type"),
                List.of("api/v2/database/checksum", "", "id", "format", "checksums"),
                List.of("api/v2/database/checksum", "/checksums", "md5", "sha1", "sha256", "sha512"),
                List.of("api/v2/database/downloads", "/downloads/0", "dataset_id", "format", "outcome", "sample", "open",
                        "created"));
        for (List<String> each : cases) {
            String path = each.get(0);
            for (String member : each.subList(2, each.size())) {
                for (boolean drop : new boolean[] {true, false}) {
                    JsonNode body = mapper.readTree(whole(path));
                    var holder = (com.fasterxml.jackson.databind.node.ObjectNode) body.at(each.get(1));
                    if (drop) {
                        assertTrue(holder.remove(member) != null, member + " is not at " + each.get(1));
                    } else {
                        holder.putNull(member);
                    }
                    String label = path + " " + each.get(1) + " " + member + (drop ? " left out" : " null");
                    StubHttpClient http = StubHttpClient.of(Map.of(path,
                            StubHttpClient.Route.ok(mapper.writeValueAsString(body))));
                    // Each object's first member is asked twice, to show the refusal retried; the
                    // rest once, since every retry waits out a backoff.
                    int retries = path.startsWith("api/v2/") || member.equals(each.get(2)) ? 1 : 0;
                    InternetData client = clientOn(http).retries(retries).build();
                    InternetDataException err = assertThrows(InternetDataException.class, () -> call(client, path), label);
                    assertEquals(ErrorKind.SERVER_ERROR, err.kind(), label);
                    assertEquals(200, err.statusCode().orElseThrow(), label);
                    assertEquals(retries + 1, http.calls.size(), label + ": retried");
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
                List.of("api/v2/database/downloads", "/downloads/0", "sample", "true"),
                List.of("1.1.1.1", "", "is_tor", "false"),
                List.of("1.1.1.1", "", "is_hosting", "yes"),
                List.of("1.1.1.1", "", "is_hosting", 1),
                List.of("1.1.1.1", "", "ip", 8),
                List.of("1.1.1.1", "", "hostname", false),
                List.of("1.1.1.1", "/cdn", "provider", 3),
                List.of("1.1.1.1", "/web/0", "port", "443"),
                List.of("1.1.1.1", "/web/0", "port", 443.5),
                List.of("1.1.1.1", "/location", "latitude", "-27.4"),
                List.of("1.1.1.1", "/asn", "asn", "13335"),
                List.of("1.1.1.1", "/abuse", "validated", "false"),
                List.of("myip", "", "is_bogon", "false"),
                List.of("api/v1/entitlement", "/usage", "requests", "12"),
                List.of("asn/9009", "", "asn", "9009"),
                List.of("asn/9009", "", "num_ips", 1.5),
                List.of("asn/9009", "/whois", "country", 5),
                List.of("domain/example.com", "", "has_a", "true"),
                List.of("domain/example.com", "/dns/mx/0", "priority", "10"));
        for (List<Object> each : cases) {
            String path = (String) each.get(0);
            JsonNode body = mapper.readTree(whole(path));
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
     * A null inside a list or map, a value outside an enum and a sample row that is no object, each
     * in an otherwise whole answer. Through 2.6.1 each came back as an answer, a {@code license_type}
     * outside its enum as null, which reads as no license at all.
     */
    @Test
    void aNullEntryAValueOutsideItsEnumOrARowThatIsNoObjectIsARetriedServerError() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<List<Object>> cases = List.of(
                List.of("api/v2/database/list", "/databases/0/versions/0", "sample_formats", Arrays.asList((Object) null)),
                List.of("api/v2/database/list", "/databases/0", "license_type", "perpetual"),
                List.of("api/v2/database/list", "/databases/0", "license_type", 1),
                List.of("api/v2/database/metadata", "", "sample", Map.of("csvgz", Arrays.asList((Object) null))),
                List.of("api/v2/database/metadata", "", "sample", Map.of("csvgz", List.of(List.of("10.0.0.0/8")))),
                List.of("api/v2/database/metadata", "", "sample", Map.of("csvgz", List.of("10.0.0.0/8"))),
                List.of("api/v2/database/metadata", "", "sample", Map.of("csvgz", List.of(5))),
                List.of("api/v2/database/metadata", "", "sample", Collections.singletonMap("csvgz", null)),
                List.of("api/v2/database/metadata", "", "sample_size", Collections.singletonMap("csvgz", null)),
                List.of("1.1.1.1", "", "web", Arrays.asList((Object) null)),
                List.of("1.1.1.1", "/bittorrent", "clients", Arrays.asList((Object) null)),
                List.of("asn/9009", "", "prefixes", Arrays.asList((Object) null)),
                List.of("asn/9009", "", "whois", Collections.singletonMap("country", null)),
                List.of("domain/example.com", "/dns", "a", Arrays.asList((Object) null)),
                List.of("api/v1/entitlement", "/apikey", "allowed_cidrs", Arrays.asList((Object) null)),
                List.of("api/v1/entitlement", "/plan", "tier", "platinum"));
        for (List<Object> each : cases) {
            String path = (String) each.get(0);
            JsonNode body = mapper.readTree(whole(path));
            JsonNode value = mapper.valueToTree(each.get(3));
            ((com.fasterxml.jackson.databind.node.ObjectNode) body.at((String) each.get(1))).set((String) each.get(2), value);
            String label = path + " " + each.get(1) + " " + each.get(2) + " = " + value;
            StubHttpClient http = StubHttpClient.of(Map.of(path, StubHttpClient.Route.ok(mapper.writeValueAsString(body))));
            InternetData client = clientOn(http).retries(1).build();
            InternetDataException err = assertThrows(InternetDataException.class, () -> call(client, path), label);
            assertEquals(ErrorKind.SERVER_ERROR, err.kind(), label);
            assertEquals(200, err.statusCode().orElseThrow(), label);
            assertEquals(2, http.calls.size(), label + ": retried");
        }

        JsonNode metadata = mapper.readTree(WHOLE.get("api/v2/database/metadata"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).set("sample",
                mapper.readTree("{\"csvgz\": [{\"ip\": \"10.0.0.0/8\", \"note\": null}]}"));
        InternetData client = clientOn(StubHttpClient.of(Map.of("api/v2/database/metadata",
                StubHttpClient.Route.ok(mapper.writeValueAsString(metadata))))).build();
        assertEquals(1, client.database().metadata("bogon_ip_v1").getSample().get("csvgz").size(),
                "a null inside a row is the row's own");
    }

    /**
     * The spec's {@code date} and {@code date-time} are RFC 3339's, and only those are read. Through
     * 2.6.1 Jackson's readers took a Unix time as a number or a numeric string, a date sent as a
     * date-time, an epoch day or an array, a padded value, an offset without its colon or minutes or
     * with seconds, no seconds, a year past four digits, and {@code ""} as no date at all.
     */
    @Test
    void aDateIsReadAsRfc3339AndNothingElse() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Object> dateTimes = List.of("2026-10-05T10:00:00", " 2026-10-05T10:00:00Z", "2026-10-05T10:00:00Z ",
                "2026-10-05T10:00:00+0500", "2026-10-05T10:00:00+05", "2026-10-05T10:00:00+05:00:30",
                "2026-10-05T10:00Z", "2026-10-05 10:00:00Z", "2026-10-05T10:00:00+24:00", "2026-10-05T10:00:00+05:60",
                "+12026-10-05T10:00:00Z", "2026-10-05T10:00:00.Z", "2026-10-05T10:00:00,5Z", "2026-10-05T10:00:60Z",
                "2026-10-05T24:00:00Z", "2026-02-30T10:00:00Z", "2026-10-5T10:00:00Z", "2026-W41-1T10:00:00Z",
                "20261005T100000Z", "2026-10-05", "1700000000", "1.5", 1700000000, 1.5, "x", "");
        List<Object> dates = List.of(" 2026-10-05", "2026-10-05 ", "2026-10-05T10:00:00", "2026-10-05T10:00:00Z",
                "+12026-10-05", "-2026-10-05", "2026-10-05Z", "20261005", "2026-W41-1", "2026-278", "2026-02-30",
                "2026-10-5", 20000, List.of(2026, 10, 5), "x", "");
        List<List<Object>> cases = new ArrayList<>();
        for (Object value : dateTimes) {
            cases.add(List.of("api/v2/database/list", "/databases/0", "starts", value));
            cases.add(List.of("api/v2/database/downloads", "/downloads/0", "created", value));
        }
        for (Object value : dates) {
            cases.add(List.of("api/v2/database/metadata", "", "updated", value));
        }
        for (List<Object> each : cases) {
            String path = (String) each.get(0);
            JsonNode body = mapper.readTree(WHOLE.get(path));
            JsonNode value = mapper.valueToTree(each.get(3));
            ((com.fasterxml.jackson.databind.node.ObjectNode) body.at((String) each.get(1))).set((String) each.get(2), value);
            String label = path + " " + each.get(2) + " = " + value;
            StubHttpClient http = StubHttpClient.of(Map.of(path, StubHttpClient.Route.ok(mapper.writeValueAsString(body))));
            InternetData client = clientOn(http).retries(1).build();
            InternetDataException err = assertThrows(InternetDataException.class, () -> call(client, path), label);
            assertEquals(ErrorKind.SERVER_ERROR, err.kind(), label);
            assertEquals(200, err.statusCode().orElseThrow(), label);
            assertEquals(2, http.calls.size(), label + ": retried");
        }

        Map<String, OffsetDateTime> readable = Map.of(
                "2026-10-05T10:00:00Z", OffsetDateTime.of(2026, 10, 5, 10, 0, 0, 0, ZoneOffset.UTC),
                "2026-10-05t10:00:00.5z", OffsetDateTime.of(2026, 10, 5, 10, 0, 0, 500_000_000, ZoneOffset.UTC),
                "2026-10-05T10:00:00-00:00", OffsetDateTime.of(2026, 10, 5, 10, 0, 0, 0, ZoneOffset.UTC),
                "2026-10-05T10:00:00.1234567891-05:30",
                OffsetDateTime.of(2026, 10, 5, 10, 0, 0, 123_456_789, ZoneOffset.ofHoursMinutes(-5, -30)),
                "2026-10-05T23:59:59+18:00", OffsetDateTime.of(2026, 10, 5, 23, 59, 59, 0, ZoneOffset.ofHours(18)));
        for (var each : readable.entrySet()) {
            JsonNode list = mapper.readTree(WHOLE.get("api/v2/database/list"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) list.at("/databases/0")).put("starts", each.getKey());
            JsonNode downloads = mapper.readTree(WHOLE.get("api/v2/database/downloads"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) downloads.at("/downloads/0")).put("created", each.getKey());
            InternetData client = clientOn(StubHttpClient.of(Map.of(
                    "api/v2/database/list", StubHttpClient.Route.ok(mapper.writeValueAsString(list)),
                    "api/v2/database/downloads", StubHttpClient.Route.ok(mapper.writeValueAsString(downloads))))).build();
            assertEquals(each.getValue(), client.database().list().get(0).getStarts(), each.getKey());
            assertEquals(each.getValue(), client.database().downloads().get(0).getCreated(), each.getKey());
        }
        InternetData client = clientOn(StubHttpClient.of(Map.of("api/v2/database/metadata",
                StubHttpClient.Route.ok(WHOLE.get("api/v2/database/metadata"))))).build();
        assertEquals(LocalDate.of(2026, 9, 4), client.database().metadata("bogon_ip_v1").getUpdated());
    }

    /**
     * A chunk the batch cannot read fails every address in it, retried; an entry it cannot read is
     * that address's failure alone, and the rest of the chunk is answered.
     */
    @Test
    void aBatchReadsItsChunkWholeAndEachEntryOnItsOwn() {
        List<String> ips = List.of("8.8.8.8", "1.1.1.1");
        for (String body : List.of("<html>gateway</html>", "{\"results\": {", "", "null", "[]", "{}",
                "{\"results\": [], \"errors\": null}")) {
            StubHttpClient http = StubHttpClient.of(Map.of()).answeringBatchWith(StubHttpClient.Route.ok(body));
            try (InternetData client = clientOn(http).retries(1).build()) {
                Map<String, BatchResult> out = client.lookupBatch(ips);
                for (String ip : ips) {
                    InternetDataException err = out.get(ip).error().orElseThrow(() -> new AssertionError(body));
                    assertEquals(ErrorKind.SERVER_ERROR, err.kind(), "'" + body + "' " + ip);
                    assertEquals(200, err.statusCode().orElseThrow(), "'" + body + "' " + ip);
                }
                assertEquals(2, http.calls.size(), "'" + body + "': retried");
            }
        }
        String whole = StubHttpClient.answer("1.1.1.1");
        for (String entry : List.of("{}", "null", "[]", "\"x\"", "{\"ip\": \"8.8.8.8\"}",
                StubHttpClient.answer("8.8.8.8", "\"is_vpn\": \"yes\""),
                StubHttpClient.answer("8.8.8.8", "\"vpn\": {\"provider\": 3}"),
                lookupBody("asn-unlimited-answer"))) {
            String body = "{\"results\": {\"8.8.8.8\": " + entry + ", \"1.1.1.1\": " + whole + "}, \"errors\": {}}";
            StubHttpClient http = StubHttpClient.of(Map.of()).answeringBatchWith(StubHttpClient.Route.ok(body));
            try (InternetData client = clientOn(http).retries(1).build()) {
                Map<String, BatchResult> out = client.lookupBatch(ips);
                InternetDataException err = out.get("8.8.8.8").error().orElseThrow(() -> new AssertionError(entry));
                assertEquals(ErrorKind.SERVER_ERROR, err.kind(), entry);
                assertEquals(200, err.statusCode().orElseThrow(), entry);
                assertEquals("1.1.1.1", out.get("1.1.1.1").orElseThrow().ip(), entry);
                assertEquals(1, http.calls.size(), entry + ": an entry is never retried");
            }
        }
        String body = "{\"results\": {\"1.1.1.1\": " + whole + "}, \"errors\": {\"8.8.8.8\": {\"status\": \"x\"}}}";
        StubHttpClient http = StubHttpClient.of(Map.of()).answeringBatchWith(StubHttpClient.Route.ok(body));
        try (InternetData client = clientOn(http).retries(1).build()) {
            InternetDataException err = client.lookupBatch(ips).get("8.8.8.8").error().orElseThrow();
            assertEquals(ErrorKind.SERVER_ERROR, err.kind(), "an unreadable error entry");
            assertEquals(200, err.statusCode().orElseThrow());
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
        // A detail object is {} under a false flag, so the spec requires none of its members.
        Set<Class<?>> unchecked = Set.of(io.internetdata.model.ClassDetail.class,
                io.internetdata.model.ProxyDetail.class, io.internetdata.model.PubproxyDetail.class,
                io.internetdata.model.CarrierDetail.class, io.internetdata.model.SatelliteDetail.class,
                io.internetdata.model.SshDetail.class, io.internetdata.model.SmtpDetail.class,
                io.internetdata.model.DnsResolverDetail.class, io.internetdata.model.BitTorrentDetail.class,
                io.internetdata.model.DisposableDetail.class);
        Set<Class<?>> reached = new LinkedHashSet<>();
        List<Class<?>> pending = new ArrayList<>(envelopes);
        pending.add(io.internetdata.model.DatabaseMetadata.class);
        pending.add(LookupResponse.class);
        pending.add(AsnLookupResponse.class);
        pending.add(DomainLookupResponse.class);
        pending.add(io.internetdata.model.Entitlement.class);
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
            if (unchecked.contains(model)) {
                assertEquals(Set.of(), nonnull, model.getSimpleName() + " has required members now");
                continue;
            }
            List<String> table = RequiredMembers.MEMBERS.get(model);
            assertTrue(table != null, model.getSimpleName() + " is not in the table");
            assertEquals(nonnull, new java.util.TreeSet<>(table), model.getSimpleName());
        }
        reached.removeAll(unchecked);
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
            case "1.1.1.1" -> client.lookup("1.1.1.1");
            case "myip" -> client.myIp();
            case "api/v1/entitlement" -> client.myEntitlement();
            case "asn/9009" -> client.lookupAsn(9009);
            case "domain/example.com" -> client.lookupDomain("example.com");
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
     * key. The corpus names the rules by ID and deliberately names no private database, because it
     * is committed into public repositories; each ID has to reach a handler here, so a rule added
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
     * No database ID is written into the library at all, so there is no second source a listing
     * could be reconstructed from.
     *
     * <p>Checked against the sources rather than argued: an ID looks like {@code <family>_v<n>}, and
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
        assertEquals(List.of(), hits, "a database ID is compiled into the library");
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

    // Each address goes out once, with the cache or without it: the cache's board alone would hide
    // a batch that sent a duplicate, which the API counts as a lookup of its own.
    @Test
    void batchDedupesShortCircuitsBogonsAndKeysByAddress() {
        JsonNode c = batchCase("dedup-bogon-and-order-free-keying");
        for (boolean cached : new boolean[] {true, false}) {
            StubHttpClient http = StubHttpClient.of(Map.of(
                    "1.1.1.1", StubHttpClient.Route.ok(StubHttpClient.answer("1.1.1.1")),
                    "8.8.8.8", StubHttpClient.Route.ok(StubHttpClient.answer("8.8.8.8"))));

            try (InternetData client = clientOn(http).cacheEnabled(cached).build()) {
                LinkedHashMap<String, BatchResult> got = client.lookupBatch(strings(c.get("input")));
                JsonNode expect = c.get("expect");

                assertEquals(strings(expect.get("keys")), new ArrayList<>(got.keySet()));
                assertEquals(expect.get("httpRequests").asInt(), http.calls.size());
                for (JsonNode bogon : expect.get("bogonKeys")) {
                    assertTrue(got.get(bogon.asText()).orElseThrow().isBogon(),
                            bogon.asText() + " should be a local answer");
                }
                assertEquals(List.of("1.1.1.1", "8.8.8.8"), http.batchIps, "cache " + cached + ": the batch body");
                // The body names the addresses alone: the API's batch also takes AS numbers and domains.
                assertEquals(List.of(List.of("ips")), http.batchMembers);
            }
        }
    }

    @Test
    void oneBadAddressDoesNotLoseTheRestOfTheBatch() {
        JsonNode c = batchCase("partial-failure-does-not-fail-the-batch");
        StubHttpClient http = StubHttpClient.of(Map.of(
                "1.1.1.1", StubHttpClient.Route.ok(StubHttpClient.answer("1.1.1.1"))));

        try (InternetData client = clientOn(http).retries(0).build()) {
            LinkedHashMap<String, BatchResult> got = client.lookupBatch(strings(c.get("input")));
            JsonNode expect = c.get("expect");

            assertEquals(strings(expect.get("keys")), new ArrayList<>(got.keySet()));
            for (JsonNode bad : expect.get("errorKeys")) {
                BatchResult entry = got.get(bad.asText());
                assertFalse(entry.isSuccess(), bad.asText() + " should carry its error");
                assertEquals(expect.get("errorKinds").get(bad.asText()).asText(),
                        wire(entry.error().orElseThrow().kind()), bad.asText());
            }
            assertFalse(got.get("1.1.1.1").orElseThrow().isTor(), "the good address still answered");
        }
    }

    @Test
    void aCacheHitIssuesNoSecondRequest() {
        JsonNode c = batchCase("cache-hit-issues-no-second-request");
        StubHttpClient http = StubHttpClient.of(Map.of(
                "1.1.1.1", StubHttpClient.Route.ok(StubHttpClient.answer("1.1.1.1"))));

        try (InternetData client = clientOn(http).build()) {
            for (int i = 0; i < c.get("repeat").asInt(); i++) {
                client.lookupBatch(strings(c.get("input")));
            }
            assertEquals(c.get("expect").get("httpRequests").asInt(), http.calls.size());
        }
    }

    @Test
    void aLargeBatchIsSentInChunksOfAThousand() {
        JsonNode c = batchCase("chunks-of-one-thousand");
        Map<String, StubHttpClient.Route> routes = new HashMap<>();
        for (String ip : strings(c.get("input"))) {
            routes.put(ip, StubHttpClient.Route.ok(StubHttpClient.answer(ip)));
        }
        StubHttpClient http = StubHttpClient.of(routes);

        try (InternetData client = clientOn(http).cacheEnabled(false).build()) {
            LinkedHashMap<String, BatchResult> got = client.lookupBatch(strings(c.get("input")));
            JsonNode expect = c.get("expect");

            assertEquals(expect.get("keyCount").asInt(), got.size());
            assertEquals(expect.get("httpRequests").asInt(), http.calls.size());
            for (String ip : strings(c.get("input"))) {
                assertEquals(ip, got.get(ip).orElseThrow().ip(), ip + " should be answered for itself");
            }
        }
    }

    @Test
    void anUncappedBatchIsChunkedRatherThanRefused() {
        JsonNode c = batchCase("uncapped-input-is-chunked");
        StubHttpClient http = StubHttpClient.echoing(Duration.ZERO);

        try (InternetData client = clientOn(http).cacheEnabled(false).build()) {
            LinkedHashMap<String, BatchResult> got = client.lookupBatch(strings(c.get("input")));
            JsonNode expect = c.get("expect");

            assertEquals(expect.get("keyCount").asInt(), got.size());
            assertEquals(expect.get("httpRequests").asInt(), http.calls.size());
            for (String ip : strings(c.get("input"))) {
                assertEquals(ip, got.get(ip).orElseThrow().ip(), ip + " should be answered for itself");
            }
        }
    }

    // A per-entry failure carries no headers, so its 429 can only be a spent allowance, and a 500
    // is the server's; neither is retried per entry, because retries belong to the call and the
    // call succeeded.
    @Test
    void anEntryErrorIsClassifiedByItsStatus() {
        JsonNode c = batchCase("an-entry-error-is-classified-by-its-status");
        StubHttpClient http = StubHttpClient.of(Map.of(
                "1.1.1.1", StubHttpClient.Route.ok(StubHttpClient.answer("1.1.1.1")),
                "8.8.8.8", new StubHttpClient.Route(429,
                        "{\"error\": \"overage cap reached; raise or remove it in Billing\"}",
                        Map.of()),
                "9.9.9.9", new StubHttpClient.Route(500, "{\"error\": \"lookup failed\"}", Map.of())));

        try (InternetData client = clientOn(http).retries(3).build()) {
            LinkedHashMap<String, BatchResult> got = client.lookupBatch(strings(c.get("input")));
            JsonNode expect = c.get("expect");

            assertEquals(strings(expect.get("keys")), new ArrayList<>(got.keySet()));
            expect.get("errorKinds").properties().forEach(entry -> {
                BatchResult answer = got.get(entry.getKey());
                assertFalse(answer.isSuccess(), entry.getKey() + " should carry its error");
                assertEquals(entry.getValue().asText(), wire(answer.error().orElseThrow().kind()),
                        entry.getKey());
            });
            assertEquals(expect.get("httpRequests").asInt(), http.calls.size());
        }
    }

    @Test
    void twoClientsNeverShareACachedAnswer() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "1.1.1.1", StubHttpClient.Route.ok(StubHttpClient.answer("1.1.1.1"))));

        try (InternetData a = clientOn(http).apiKey("key-a").build();
                InternetData b = clientOn(http).apiKey("key-b").build()) {
            a.lookup("1.1.1.1");
            b.lookup("1.1.1.1");
            // Two keys can be on different plans and so entitled to different fields; a shared
            // cache would serve one of them the other's shape.
            assertEquals(2, http.calls.size());
        }
    }

    @Test
    void cachingCanBeTurnedOff() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "1.1.1.1", StubHttpClient.Route.ok(StubHttpClient.answer("1.1.1.1"))));

        try (InternetData client = clientOn(http).cacheEnabled(false).build()) {
            client.lookup("1.1.1.1");
            client.lookup("1.1.1.1");
            assertEquals(2, http.calls.size());
        }
    }

    /** An AS or a domain is asked every time: neither is cached. */
    @Test
    void anAsOrADomainIsNeverCached() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "asn/9009", StubHttpClient.Route.ok(lookupBody("asn-unlimited-answer")),
                "domain/example.com", StubHttpClient.Route.ok(lookupBody("domain-unlimited-answer"))));

        try (InternetData client = clientOn(http).build()) {
            client.lookupAsn(9009);
            client.lookupAsn(9009);
            client.lookupDomain("example.com");
            client.lookupDomain("example.com");
            assertEquals(4, http.calls.size());
        }
    }

    private static InternetData.Builder clientOn(StubHttpClient http) {
        return InternetData.builder().httpClient(http).apiKey("k");
    }

    // The corpus spells a kind the way the wire does, which is the enum name in lower case.
    private static String wire(ErrorKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    // An accessor's value as the wire would carry it, so the corpus's numbers, strings and flags
    // compare whatever Java type holds them.
    private static JsonNode wire(Object value) {
        return normal(MAPPER.valueToTree(value));
    }

    private static JsonNode normal(JsonNode node) {
        return node.isIntegralNumber() ? MAPPER.getNodeFactory().numberNode(node.asLong()) : node;
    }

    private static JsonNode batchCase(String name) {
        for (JsonNode c : data.get("batch")) {
            if (c.get("name").asText().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no batch case named " + name);
    }

    private static String lookupBody(String name) {
        for (JsonNode c : data.get("lookup")) {
            if (c.get("name").asText().equals(name)) {
                return c.get("body").toString();
            }
        }
        throw new IllegalArgumentException("no lookup case named " + name);
    }

    private static String path(String call, String target) {
        return switch (call) {
            case "lookup" -> target;
            case "lookupAsn" -> "asn/" + target;
            case "lookupDomain" -> "domain/" + target;
            default -> throw new IllegalArgumentException("the corpus names a call this SDK lacks: " + call);
        };
    }

    private static Object lookUp(InternetData client, String call, String target) {
        return switch (call) {
            case "lookup" -> client.lookup(target);
            case "lookupAsn" -> client.lookupAsn(Long.parseLong(target));
            case "lookupDomain" -> client.lookupDomain(target);
            default -> throw new IllegalArgumentException("the corpus names a call this SDK lacks: " + call);
        };
    }

    // Present and empty: a detail object as {} reads it, or an empty list.
    private static boolean isEmpty(Object value) throws IOException {
        if (value instanceof Collection<?> list) {
            return list.isEmpty();
        }
        return value.equals(MAPPER.treeToValue(MAPPER.createObjectNode(), value.getClass()));
    }

    // The corpus names members the way the wire does, so every claim it makes has to reach the
    // matching accessor. An unknown name is a corpus that grew a member this binding has not.
    private static Optional<?> member(Object answer, String wire) {
        if (answer instanceof Result r) {
            return member(r, wire);
        }
        if (answer instanceof AsnResult r) {
            return member(r, wire);
        }
        return member((DomainResult) answer, wire);
    }

    private static Optional<?> member(Result r, String wire) {
        return switch (wire) {
            case "ip" -> Optional.of(r.ip());
            case "is_bogon" -> Optional.of(r.isBogon());
            case "is_vpn" -> r.isVpn();
            case "is_tor" -> Optional.of(r.isTor());
            case "is_relay" -> Optional.of(r.isRelay());
            case "is_resproxy" -> r.isResproxy();
            case "is_dcproxy" -> r.isDcproxy();
            case "is_mobproxy" -> r.isMobproxy();
            case "is_pubproxy" -> r.isPubproxy();
            case "is_hosting" -> r.isHosting();
            case "is_cdn" -> r.isCdn();
            case "is_anycast" -> Optional.of(r.isAnycast());
            case "is_carrier" -> Optional.of(r.isCarrier());
            case "is_satellite" -> Optional.of(r.isSatellite());
            case "is_ssh" -> Optional.of(r.isSsh());
            case "is_smtp" -> Optional.of(r.isSmtp());
            case "is_web" -> Optional.of(r.isWeb());
            case "is_dns_resolver" -> Optional.of(r.isDnsResolver());
            case "is_bittorrent" -> Optional.of(r.isBittorrent());
            case "vpn" -> r.vpn();
            case "tor" -> Optional.of(r.tor());
            case "relay" -> Optional.of(r.relay());
            case "resproxy" -> r.resproxy();
            case "dcproxy" -> r.dcproxy();
            case "mobproxy" -> r.mobproxy();
            case "pubproxy" -> r.pubproxy();
            case "hosting" -> r.hosting();
            case "cdn" -> r.cdn();
            case "carrier" -> r.carrier();
            case "satellite" -> r.satellite();
            case "ssh" -> r.ssh();
            case "smtp" -> r.smtp();
            case "web" -> r.web();
            case "dns_resolver" -> r.dnsResolver();
            case "bittorrent" -> r.bittorrent();
            case "location" -> Optional.of(r.location());
            case "asn" -> Optional.of(r.asn());
            case "company" -> Optional.of(r.company());
            case "hostname" -> Optional.of(r.hostname());
            case "abuse" -> Optional.of(r.abuse());
            case "whois" -> r.whois();
            case "rwhois" -> r.rwhois();
            case "hosted_domains" -> r.hostedDomains();
            case "trial_expired" -> r.trialExpired();
            default -> throw new IllegalArgumentException("the corpus names a member this SDK lacks: " + wire);
        };
    }

    private static Optional<?> member(AsnResult r, String wire) {
        return switch (wire) {
            case "asn" -> Optional.ofNullable(r.raw().getAsn()).map(asn -> r.asn());
            case "name" -> Optional.ofNullable(r.name());
            case "country" -> Optional.ofNullable(r.country());
            case "registry" -> Optional.ofNullable(r.registry());
            case "allocated" -> Optional.ofNullable(r.allocated());
            case "domain" -> Optional.ofNullable(r.domain());
            case "type" -> r.type();
            case "num_ips" -> r.numIps();
            case "num_prefixes4" -> r.numPrefixes4();
            case "num_prefixes6" -> r.numPrefixes6();
            case "rpki_valid" -> r.rpkiValid();
            case "rpki_invalid" -> r.rpkiInvalid();
            case "rpki_not_found" -> r.rpkiNotFound();
            case "whois" -> r.whois();
            case "prefixes" -> r.prefixes();
            case "trial_expired" -> r.trialExpired();
            default -> throw new IllegalArgumentException("the corpus names a member this SDK lacks: " + wire);
        };
    }

    private static Optional<?> member(DomainResult r, String wire) {
        return switch (wire) {
            case "domain" -> Optional.ofNullable(r.domain());
            case "is_registered" -> Optional.ofNullable(r.raw().getIsRegistered()).map(b -> r.isRegistered());
            case "is_disposable" -> r.isDisposable();
            case "has_a" -> Optional.ofNullable(r.raw().getHasA()).map(b -> r.hasA());
            case "has_aaaa" -> Optional.ofNullable(r.raw().getHasAaaa()).map(b -> r.hasAaaa());
            case "has_mx" -> Optional.ofNullable(r.raw().getHasMx()).map(b -> r.hasMx());
            case "has_ns" -> Optional.ofNullable(r.raw().getHasNs()).map(b -> r.hasNs());
            case "has_cname" -> Optional.ofNullable(r.raw().getHasCname()).map(b -> r.hasCname());
            case "dns" -> r.dns();
            case "ip_ranges" -> r.ipRanges();
            case "disposable" -> r.disposable();
            case "trial_expired" -> r.trialExpired();
            default -> throw new IllegalArgumentException("the corpus names a member this SDK lacks: " + wire);
        };
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
