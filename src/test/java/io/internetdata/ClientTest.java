package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.internetdata.middleware.Core;
import io.internetdata.middleware.Lookup;
import io.internetdata.middleware.Options;
import io.internetdata.model.Entitlement;
import io.internetdata.model.EntitlementPlan;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The builder, the settings a caller is most likely to reach for, and the Java-specific API surface,
 * as distinct from the shared corpus in {@link ConformanceTest}.
 */
class ClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode data;

    private static final List<String> ADDRESSES = addresses(6001);

    @BeforeAll
    static void loadCorpus() throws IOException {
        data = MAPPER.readTree(Path.of("testdata", "testdata.json").toFile());
    }

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

    /**
     * The key is trimmed, blanks alone are no key, and a control character in it is refused where
     * it is set. Before 2.4.5 a key of blanks went out as a bare {@code Bearer}, a padded one padded
     * and a tab inside one as itself, while a CR, LF, NUL or DEL failed every call with the JDK's
     * raw {@code IllegalArgumentException}, whose message quoted the key.
     */
    @Test
    void theKeyIsTrimmedAndAControlCharacterInItIsRefusedWhereItIsSet() {
        StubHttpClient http = StubHttpClient.of(Map.of("api/v2/database/list",
                StubHttpClient.Route.ok("{\"databases\": []}")));

        InternetData.builder().httpClient(http).apiKey(" \tsecret-key\r\n").build().database().list();
        InternetData.builder().httpClient(http).apiKey(" \t \n").build().database().list();

        assertEquals("Bearer secret-key", http.authorizations.get(0));
        assertNull(http.authorizations.get(1), "a key of blanks still sent an Authorization header");
        for (String key : List.of("se\tcret", "se\rcret", "se\ncret", "se\u0000cret", "se\u007fcret",
                "secret\r\nX-Injected: 1", "se\u0085cret")) {
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> InternetData.builder().apiKey(key), "accepted " + key);
            assertFalse(err.getMessage().contains("cret"), "the refusal quoted the key: " + err.getMessage());
        }
        assertEquals(2, http.calls.size());
    }

    /**
     * The JDK sends a header as ASCII: through 2.6.1 a key holding {@code é} went out with a
     * {@code ?} in its place, and one holding a character past U+00FF failed every call with the
     * JDK's raw {@code IllegalArgumentException}, whose message quoted the key.
     */
    @Test
    void aKeyHoldingACharacterOutsideAsciiIsRefusedWhereItIsSet() {
        for (String key : List.of("s\u00e9cret", "s\u00ffcret", "s\u0100cret", "s\u4e2dcret", "s\ud83d\ude00cret")) {
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> InternetData.builder().apiKey(key), "accepted " + key);
            assertFalse(err.getMessage().contains("cret"), "the refusal quoted the key: " + err.getMessage());
        }
        assertDoesNotThrow(() -> InternetData.builder().apiKey("id_k~!#$%&'*+-.^_`|0123456789"));
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
                    refused + " was accepted by the builder");
            assertThrows(IllegalArgumentException.class,
                    () -> new LookupOptions().requestTimeout(refused),
                    refused + " was accepted per call");
            assertThrows(IllegalArgumentException.class,
                    () -> new BatchOptions().requestTimeout(refused),
                    refused + " was accepted per call");
            assertThrows(IllegalArgumentException.class,
                    () -> new OauthOptions().requestTimeout(refused),
                    refused + " was accepted per call");
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
        StubHttpClient http = StubHttpClient.responding(path -> path.startsWith("api/")
                ? StubHttpClient.Route.ok("{\"databases\": []}")
                : StubHttpClient.Route.ok(StubHttpClient.answer("146.70.116.130")));

        for (String baseUrl : new String[] {"https://api-staging.internetdata.io/",
                "https://api-staging.internetdata.io//"}) {
            try (InternetData client = InternetData.builder().httpClient(http).apiKey("k")
                    .cacheEnabled(false).baseUrl(baseUrl).build()) {
                client.lookup("146.70.116.130");
                client.database().list();
            }
        }
        assertEquals(List.of(
                "https://api-staging.internetdata.io/146.70.116.130",
                "https://api-staging.internetdata.io/api/v2/database/list",
                "https://api-staging.internetdata.io/146.70.116.130",
                "https://api-staging.internetdata.io/api/v2/database/list"), http.calls);
    }

    /** The lookups are served on the API's host, and the database calls answer there as well. */
    @Test
    void theDefaultBaseUrlIsTheApiHost() {
        StubHttpClient http = StubHttpClient.responding(path -> path.startsWith("api/")
                ? StubHttpClient.Route.ok("{\"databases\": []}")
                : StubHttpClient.Route.ok(StubHttpClient.answer("146.70.116.130")));

        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            client.lookup("146.70.116.130");
            client.database().list();
        }
        assertEquals("https://api.internetdata.io", InternetData.DEFAULT_BASE_URL);
        assertEquals(List.of("https://api.internetdata.io/146.70.116.130",
                "https://api.internetdata.io/api/v2/database/list"), http.calls);
    }

    /**
     * The authorization server is the API's apex, so the OAuth calls drop {@code api.} from the base
     * URL and make {@code api-<env>.} {@code <env>.}; any other base URL is theirs as given.
     */
    @Test
    void theOauthCallsGoToTheApexOfTheApiHost() {
        Map<String, String> apexes = new LinkedHashMap<>();
        apexes.put(InternetData.DEFAULT_BASE_URL, "https://internetdata.io");
        apexes.put("https://api-staging.internetdata.io", "https://staging.internetdata.io");
        apexes.put("https://API.internetdata.io/", "https://internetdata.io");
        apexes.put("https://internetdata.io", "https://internetdata.io");
        apexes.put("https://staging.internetdata.io", "https://staging.internetdata.io");
        apexes.put("http://127.0.0.1:8080", "http://127.0.0.1:8080");
        apexes.put("https://apis.example.com", "https://apis.example.com");
        apexes.put("https://api.example.com:8443/prefix", "https://example.com:8443/prefix");
        apexes.forEach((baseUrl, apex) -> {
            OauthStub http = OauthStub.answering(OauthStub.Answer.of(503, "{}"));
            try (InternetData client = InternetData.builder().httpClient(http).apiKey("k").retries(0)
                    .baseUrl(baseUrl).build()) {
                assertTrue(client.oauth().authorizationUrl("cid", "http://127.0.0.1:8765/callback", "challenge")
                        .startsWith(apex + "/oauth/authorize?"), baseUrl);
                assertThrows(InternetDataException.class, () -> client.oauth().metadata(), baseUrl);
                assertEquals(java.net.URI.create(apex + "/.well-known/oauth-authorization-server"),
                        http.sent.get(0).uri(), baseUrl);
            }
        });
    }

    @Test
    void isBogonIsOnTheClientAndAgreesWithTheStandaloneForm() {
        try (InternetData client = InternetData.create()) {
            for (JsonNode c : data.get("isBogon")) {
                String ip = c.get("ip").asText();
                assertEquals(c.get("expect").asBoolean(), client.isBogon(ip),
                        ip + " (" + c.get("why").asText() + ")");
                assertEquals(Bogon.isBogon(ip), client.isBogon(ip),
                        ip + ": client and standalone form disagree");
            }
        }
    }

    @Test
    void batchConcurrencyIsConfigurablePerCall() {
        StubHttpClient http = StubHttpClient.echoing(Duration.ofMillis(20));
        try (InternetData client = InternetData.builder()
                .httpClient(http).cacheEnabled(false).build()) {
            client.lookupBatch(ADDRESSES, new BatchOptions().concurrency(3));

            assertEquals(7, http.calls.size(), "one request per chunk of 1000");
            assertTrue(http.peak.get() <= 3,
                    "peak in flight was " + http.peak.get() + ", expected at most 3");
            assertTrue(http.peak.get() > 1, "requests should still overlap");
        }
    }

    @Test
    void aPerCallConcurrencyOverridesTheClientDefault() {
        StubHttpClient http = StubHttpClient.echoing(Duration.ofMillis(20));
        // Instance default of 2, raised to 6 for this one batch.
        try (InternetData client = InternetData.builder()
                .httpClient(http).cacheEnabled(false).concurrency(2).build()) {
            client.lookupBatch(ADDRESSES, new BatchOptions().concurrency(6));

            assertTrue(http.peak.get() > 2,
                    "override ignored: peak was " + http.peak.get() + ", expected above 2");
            assertTrue(http.peak.get() <= 6,
                    "peak in flight was " + http.peak.get() + ", expected at most 6");
        }
    }

    @Test
    void withoutAnOverrideTheClientConcurrencyStillApplies() {
        StubHttpClient http = StubHttpClient.echoing(Duration.ofMillis(20));
        try (InternetData client = InternetData.builder()
                .httpClient(http).cacheEnabled(false).concurrency(2).build()) {
            client.lookupBatch(ADDRESSES);

            assertTrue(http.peak.get() <= 2,
                    "peak in flight was " + http.peak.get() + ", expected at most 2");
        }
    }

    @Test
    void retriesAreConfigurablePerCall() {
        StubHttpClient http = StubHttpClient.of(Map.of("9.9.9.9",
                new StubHttpClient.Route(500, "{\"error\": \"lookup failed\"}", Map.of())));
        try (InternetData client = InternetData.builder()
                .httpClient(http).cacheEnabled(false).retries(0).build()) {
            assertThrows(InternetDataException.class,
                    () -> client.lookup("9.9.9.9", new LookupOptions().retries(2)));

            // 1 initial attempt plus 2 retries, rather than the instance's 0.
            assertEquals(3, http.calls.size());
        }
    }

    @Test
    void aNegativeRetryCountIsRefusedWhereItIsSet() {
        for (int retries : new int[] {-1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new LookupOptions().retries(retries));
            assertThrows(IllegalArgumentException.class, () -> new BatchOptions().retries(retries));
            assertThrows(IllegalArgumentException.class, () -> new Options<String>().retries(retries));
        }
        assertEquals(0, new LookupOptions().retries(0).retriesOrNull());
    }

    // The transport here ignores the request's own timeout and outlasts every bound, so only a race
    // the client owns ends the call in time, and only the per-call bound names 200ms.
    @Test
    void aPerCallTimeoutBelowTheClientsFiresAsARetryableNetworkError() {
        StubHttpClient http = StubHttpClient.hanging();
        LookupOptions quick = new LookupOptions().requestTimeout(Duration.ofMillis(200));
        try (InternetData client = InternetData.builder().httpClient(http).cacheEnabled(false)
                .retries(0).requestTimeout(Duration.ofSeconds(10)).build()) {
            Map<String, Executable> calls = new LinkedHashMap<>();
            calls.put("lookup", () -> client.lookup("9.9.9.9", quick));
            calls.put("myIp", () -> client.myIp(quick));
            calls.put("myEntitlement", () -> client.myEntitlement(quick));
            calls.put("lookupAsn", () -> client.lookupAsn(9009, quick));
            calls.put("lookupDomain", () -> client.lookupDomain("example.com", quick));

            for (Map.Entry<String, Executable> call : calls.entrySet()) {
                long started = System.nanoTime();
                InternetDataException e = assertThrows(InternetDataException.class, call.getValue());
                long took = Duration.ofNanos(System.nanoTime() - started).toMillis();

                assertEquals(ErrorKind.NETWORK, e.kind(), call.getKey());
                assertTrue(e.retryable(), call.getKey());
                assertTrue(e.getMessage().contains("after 200ms"), call.getKey() + ": " + e.getMessage());
                assertTrue(took < 5000, call.getKey() + " took " + took + "ms, past its own bound");
            }
        }
    }

    @Test
    void aPerCallTimeoutBoundsEveryChunkOfABatch() {
        StubHttpClient http = StubHttpClient.hanging();
        try (InternetData client = InternetData.builder().httpClient(http).cacheEnabled(false)
                .retries(0).requestTimeout(Duration.ofSeconds(10)).build()) {
            long started = System.nanoTime();
            LinkedHashMap<String, BatchResult> got = client.lookupBatch(addresses(1500),
                    new BatchOptions().requestTimeout(Duration.ofMillis(200)));
            long took = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertEquals(2, http.calls.size(), "one request per chunk");
            assertEquals(1500, got.size());
            for (BatchResult answer : got.values()) {
                InternetDataException e = answer.error().orElseThrow();
                assertEquals(ErrorKind.NETWORK, e.kind());
                assertTrue(e.getMessage().contains("after 200ms"), e.getMessage());
            }
            assertTrue(took < 5000, "the batch took " + took + "ms, past its own bound");
        }
    }

    // Per ATTEMPT: a timeout is retryable, and each retry starts a fresh budget rather than
    // inheriting whatever the first attempt left.
    @Test
    void aTimedOutAttemptIsRetriedWithAFreshBudget() {
        StubHttpClient http = StubHttpClient.hanging();
        try (InternetData client = InternetData.builder().httpClient(http).cacheEnabled(false)
                .retries(0).build()) {
            long started = System.nanoTime();
            assertThrows(InternetDataException.class, () -> client.lookup("9.9.9.9",
                    new LookupOptions().retries(2).requestTimeout(Duration.ofMillis(150))));
            long took = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertEquals(3, http.calls.size());
            assertTrue(took >= 450, "took " + took + "ms, less than three attempts of 150ms each");
        }
    }

    // An injected client keeps its own, looser bound; the request path's still applies per lookup.
    @Test
    void theMiddlewareTimeoutHoldsForAnInjectedClient() {
        try (InternetData client = InternetData.builder().httpClient(StubHttpClient.hanging())
                .cacheEnabled(false).requestTimeout(Duration.ofSeconds(10)).build()) {
            Core<String> core = new Core<>(new Options<String>().client(client)
                    .timeout(Duration.ofMillis(200)), request -> request);

            Lookup answer = core.evaluate("9.9.9.9");
            InternetDataException e = answer.error().orElseThrow();
            assertEquals(ErrorKind.NETWORK, e.kind());
            assertTrue(e.getMessage().contains("after 200ms"), e.getMessage());
            assertFalse(answer.isBlocked(), "a failed lookup fails open");
        }
    }

    // No cap on what one call takes: chunking to the endpoint's 1000 is the client's job.
    @Test
    void aBatchOfAnyLengthIsSentAsChunksOfAThousand() {
        List<String> ips = addresses(2500);
        StubHttpClient http = StubHttpClient.echoing(Duration.ZERO);
        try (InternetData client = InternetData.builder().httpClient(http).cacheEnabled(false).build()) {
            LinkedHashMap<String, BatchResult> got = client.lookupBatch(ips);

            assertEquals(3, http.calls.size(), "three requests in all");
            assertEquals(List.of(500, 1000, 1000),
                    http.batchSizes.stream().sorted().collect(Collectors.toList()),
                    "every request is a POST /batch of at most 1000");
            assertEquals(ips, new ArrayList<>(got.keySet()));
            for (String ip : ips) {
                assertEquals(ip, got.get(ip).orElseThrow().ip(), ip + " should be answered for itself");
            }
        }
    }

    // A semaphore with no permits would admit no chunk, and the batch would wait forever.
    @Test
    void aPerCallConcurrencyBelowOneIsRefusedBeforeAnyRequest() {
        StubHttpClient http = StubHttpClient.echoing(Duration.ZERO);
        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            for (int concurrency : new int[] {0, -1}) {
                BatchOptions options = new BatchOptions().concurrency(concurrency);
                // Bounded from outside: an unrefused value blocks the calling thread for good.
                InternetDataException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                        InternetDataException.class, () -> client.lookupBatch(List.of("1.1.1.1"), options)));
                assertEquals(ErrorKind.BAD_REQUEST, e.kind());
                assertEquals("concurrency must be at least 1, got " + concurrency, e.getMessage());
            }
            assertEquals(0, http.calls.size());
        }
        assertThrows(IllegalArgumentException.class, () -> InternetData.builder().concurrency(0));
    }

    @Test
    void aPaidMemberIsEmptyRatherThanFalse() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "1.1.1.1", StubHttpClient.Route.ok(StubHttpClient.answer("1.1.1.1")),
                "8.8.8.8", StubHttpClient.Route.ok(StubHttpClient.answer("8.8.8.8", "\"is_hosting\": false"))));

        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            Result free = client.lookup("1.1.1.1");
            assertTrue(free.isHosting().isEmpty(), "a plan that omits the field answers nothing");
            assertFalse(free.isHosting().orElse(false), "orElse(false) still reads false");
            assertTrue(free.web().isEmpty(), "a list the plan omits is absent, not empty");

            Result paid = client.lookup("8.8.8.8");
            assertEquals(Optional.of(false), paid.isHosting(),
                    "a plan that includes the field and answers false keeps the false");
        }
    }

    @Test
    void asyncLookupsResolveToTheSameAnswers() {
        StubHttpClient http = StubHttpClient.echoing(Duration.ofMillis(5));
        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            assertEquals("1.1.1.1", client.lookupAsync("1.1.1.1").join().ip());

            LinkedHashMap<String, BatchResult> got = client.lookupBatchAsync(ADDRESSES).join();
            assertEquals(ADDRESSES, new ArrayList<>(got.keySet()));
            assertTrue(got.values().stream().allMatch(BatchResult::isSuccess));
        }
        StubHttpClient other = StubHttpClient.of(Map.of(
                "asn/9009", StubHttpClient.Route.ok(lookupBody("asn-unlimited-answer")),
                "domain/example.com", StubHttpClient.Route.ok(lookupBody("domain-unlimited-answer"))));
        try (InternetData client = InternetData.builder().httpClient(other).build()) {
            assertEquals(9009, client.lookupAsnAsync(9009).join().asn());
            assertEquals("example.com", client.lookupDomainAsync("example.com").join().domain());
        }
    }

    @Test
    void aSuppliedExecutorIsNotShutDownWithTheClient() {
        ExecutorService executor = Executors.newCachedThreadPool();
        try (InternetData client = InternetData.builder()
                .httpClient(StubHttpClient.echoing(Duration.ZERO)).executor(executor).build()) {
            client.lookup("1.1.1.1");
        }
        assertFalse(executor.isShutdown(), "the client must not close an executor it was handed");
        executor.shutdown();
    }

    /**
     * The API answers an AS or a domain at the lookup's path too, in a shape the lookup cannot read,
     * so a string that is not an address is refused before anything is sent, naming the two calls
     * that take one. A batch's addresses are the API's to refuse, one by one.
     */
    @Test
    void aLookupOfSomethingThatIsNotAnAddressIsRefusedBeforeSending() {
        StubHttpClient http = StubHttpClient.responding(path -> path.equals("notanip")
                ? new StubHttpClient.Route(400, "{\"error\": \"not a valid IP address, AS number or domain\"}", Map.of())
                : StubHttpClient.Route.ok(StubHttpClient.answer(path)));
        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            for (String target : List.of("AS15169", "15169", "example.com", "notanip", "", " 1.1.1.1", "1.2.3",
                    "1.2.3.4.5", "256.1.1.1", "::g", "1::2::3", "[::1]", "fe80::1%eth0")) {
                InternetDataException e = assertThrows(InternetDataException.class, () -> client.lookup(target),
                        "'" + target + "' was looked up");
                assertEquals(ErrorKind.BAD_REQUEST, e.kind(), target);
                assertFalse(e.retryable(), target);
                assertTrue(e.getMessage().contains("lookupAsn") && e.getMessage().contains("lookupDomain"),
                        target + ": " + e.getMessage());
            }
            assertEquals("'example.com' is not an IP address: look up an AS with lookupAsn, and a domain with"
                    + " lookupDomain", assertThrows(InternetDataException.class,
                            () -> client.lookup("example.com")).getMessage(), "every SDK's words");
            java.util.concurrent.CompletionException async = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> client.lookupAsync("example.com").join());
            assertEquals(ErrorKind.BAD_REQUEST, ((InternetDataException) async.getCause()).kind());
            assertEquals(0, http.calls.size(), "a string that is not an address reached the network");

            for (String ip : List.of("1.1.1.1", "2606:4700:4700::1111", "::ffff:8.8.8.8", "2A00:1450:4001::1")) {
                client.lookup(ip);
            }
            assertEquals(4, http.calls.size(), "every address is looked up");
            assertTrue(client.lookupBatch(List.of("1.1.1.1", "notanip")).get("notanip").error().isPresent());
            assertEquals(List.of("notanip"), http.batchIps, "a batch sends what the cache lacks, as given");
        }
    }

    /** An AS number is sent as given, and a domain as given, escaped for the path. */
    @Test
    void anAsAndADomainAreSentAsGiven() {
        StubHttpClient http = StubHttpClient.responding(path -> StubHttpClient.Route.ok(path.startsWith("asn/")
                ? lookupBody("asn-unlimited-answer") : lookupBody("domain-unlimited-answer")));

        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            client.lookupAsn(9009);
            client.lookupAsn(4294967295L);
            client.lookupAsn(-1);
            client.lookupDomain("Example.COM");
            client.lookupDomain("a b/c?d#e");
            assertThrows(NullPointerException.class, () -> client.lookupDomain(null));
        }
        assertEquals(List.of("https://api.internetdata.io/asn/9009",
                "https://api.internetdata.io/asn/4294967295",
                "https://api.internetdata.io/asn/-1",
                "https://api.internetdata.io/domain/Example.COM",
                "https://api.internetdata.io/domain/a%20b%2Fc%3Fd%23e"), http.calls);
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

    private static final String ENTITLEMENT_BODY = """
            {
              "org_id": "85bb51e4-2eb6-4a31-8e4d-02ba8b98fe61",
              "apikey": {
                "id": "0ab424cc-7619-4dad-b027-afacdc2cedb0",
                "expires": null,
                "allowed_cidrs": []
              },
              "plan": {"key": "max", "tier": "max"},
              "usage": {
                "requests": 580,
                "quota": 5000000,
                "hard_limit": null,
                "window_start": "2026-09-04T07:00:00Z",
                "window_end": "2026-10-04T07:00:00Z"
              }
            }
            """;

    @Test
    void myIpLooksUpTheCallingAddress() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "myip", StubHttpClient.Route.ok(StubHttpClient.answer("45.83.91.1", "\"is_vpn\": true"))));

        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            Result result = client.myIp();
            assertEquals("45.83.91.1", result.ip());
            assertEquals(Optional.of(true), result.isVpn());
        }
    }

    @Test
    void myIpIsNotCached() {
        // The cache is keyed by address, and which address this is IS the question.
        StubHttpClient http = StubHttpClient.of(Map.of(
                "myip", StubHttpClient.Route.ok(StubHttpClient.answer("45.83.91.1"))));

        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            client.myIp();
            client.myIp();
            assertEquals(2, http.calls.size());
        }
    }

    @Test
    void myEntitlementReportsThePlanAndTheUsage() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v1/entitlement", StubHttpClient.Route.ok(ENTITLEMENT_BODY)));

        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            Entitlement ent = client.myEntitlement();
            assertEquals("max", ent.getPlan().getKey());
            assertEquals(EntitlementPlan.TierEnum.MAX, ent.getPlan().getTier());
            assertEquals(580L, ent.getUsage().getRequests());
            assertEquals(5000000L, ent.getUsage().getQuota());
            // Null means NEVER stop, which is not the same as a limit of zero.
            assertNull(ent.getUsage().getHardLimit());
            assertTrue(ent.getApikey().getAllowedCidrs().isEmpty());
        }
    }

    @Test
    void myEntitlementIsNotCached() {
        // The whole point is what has been spent.
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v1/entitlement", StubHttpClient.Route.ok(ENTITLEMENT_BODY)));

        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            client.myEntitlement();
            client.myEntitlement();
            assertEquals(2, http.calls.size());
        }
    }

    @Test
    void myEntitlementSurfacesAnUnauthorizedKey() {
        StubHttpClient http = StubHttpClient.of(Map.of(
                "api/v1/entitlement",
                new StubHttpClient.Route(401, "{\"error\": \"invalid API key\"}", Map.of())));

        try (InternetData client = InternetData.builder().httpClient(http).retries(0).build()) {
            InternetDataException e = assertThrows(InternetDataException.class, client::myEntitlement);
            assertEquals(ErrorKind.UNAUTHORIZED, e.kind());
            assertEquals("invalid API key", e.getMessage());
        }
    }

    // Enough addresses for seven chunks of the batch endpoint's 1000, so a concurrency bound has
    // something to bound: one request per chunk, and only the chunks overlap.
    private static List<String> addresses(int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add("9." + (1 + i / 65536) + "." + (i / 256 % 256) + "." + (i % 256));
        }
        return List.copyOf(out);
    }

    private static String lookupBody(String name) {
        for (JsonNode c : data.get("lookup")) {
            if (c.get("name").asText().equals(name)) {
                return c.get("body").toString();
            }
        }
        throw new IllegalArgumentException("no lookup case named " + name);
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
