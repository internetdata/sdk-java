package io.internetdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.internetdata.internal.ApiClient;
import io.internetdata.middleware.Bound;
import io.internetdata.middleware.Condition;
import io.internetdata.middleware.Core;
import io.internetdata.middleware.Lookup;
import io.internetdata.middleware.Options;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The middleware half of the shared conformance corpus, which every framework middleware in every
 * language asserts.
 *
 * <p>The corpus is language-neutral JSON, so a bound arrives as an object with gte/gt/lte/lt keys
 * and an any-of as an array. Rebuilding them into the Java types here is what keeps the corpus
 * readable by twelve languages instead of carrying one language's spelling.
 */
class MiddlewareConformanceTest {
    private static final ObjectMapper MAPPER = ApiClient.createDefaultObjectMapper();
    private static final Set<String> BOUND_KEYS = Set.of("gte", "gt", "lte", "lt");
    private static JsonNode middleware;
    private static JsonNode lookup;

    @BeforeAll
    static void loadCorpus() throws IOException {
        JsonNode corpus = MAPPER.readTree(Path.of("testdata", "testdata.json").toFile());
        middleware = corpus.get("middleware");
        lookup = corpus.get("lookup");
    }

    @Test
    void corpusConditions() throws IOException {
        for (JsonNode c : middleware.get("conditions")) {
            String why = c.get("name").asText() + ": " + c.get("why").asText();
            Result result = resultFor(c);
            List<Map<String, Object>> conditions = toConditions(c.get("condition"));

            assertEquals(c.get("expect").get("blocked").asBoolean(),
                    Condition.matches(conditions, result), why);

            List<String> missing = new ArrayList<>(Condition.missingMembers(conditions, result));
            List<String> want = new ArrayList<>();
            c.get("expect").get("missing").forEach(n -> want.add(n.asText()));
            Collections.sort(missing);
            Collections.sort(want);
            assertEquals(want, missing, why);
        }
    }

    @Test
    void corpusRefusesAConditionThatConstrainsNothing() {
        for (JsonNode c : middleware.get("invalidConditions")) {
            String why = c.get("name").asText() + ": " + c.get("why").asText();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Condition.validate(toConditions(c.get("condition"))), why);
            assertTrue(e.getMessage().contains("constrains nothing"), why);
        }
    }

    // A bare mount attaches the record, and a country condition refuses on Unlimited's answer, which
    // every keyless lookup gets.
    @Test
    void aCountryConditionBlocksOnUnlimitedsAnswerAndAnotherCountryPasses() throws IOException {
        JsonNode unlimited = answer("unlimited-answer");
        String ip = unlimited.get("ip").asText();
        for (Map.Entry<String, Boolean> country : Map.of("AT", true, "DE", false).entrySet()) {
            try (InternetData client = serving(unlimited)) {
                Core<String> core = new Core<>(new Options<String>().client(client)
                        .blockCondition(Map.of("location", Map.of("country", country.getKey()))),
                        request -> ip);

                Lookup found = core.evaluate("request");
                assertEquals("AT", found.result().orElseThrow().location().getCountry());
                assertEquals(9009, found.result().orElseThrow().asn().getAsn());
                assertEquals(country.getValue(), found.isBlocked(), country.getKey());
            }
        }
    }

    // location is on every plan and its city is not, so a city condition on Unlimited can never
    // fire: it is reported by its path, not passed over because location itself was served.
    @Test
    void aPaidMemberInsideAnObjectEveryPlanGetsIsWarnedAboutByItsPath() throws IOException {
        JsonNode unlimited = answer("unlimited-answer");
        List<String> warnings = new ArrayList<>();
        try (InternetData client = serving(unlimited)) {
            Core<String> core = new Core<>(new Options<String>().client(client)
                    .blockCondition(Map.of("location", Map.of("city", "Vienna")))
                    .onWarn(warnings::add), request -> unlimited.get("ip").asText());

            assertFalse(core.evaluate("request").isBlocked());
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("location.city"), warnings.get(0));
        }
    }

    // The client refuses what is not an address before sending it, so a header that carried
    // something else is a failed lookup like any other: let through.
    @Test
    void aSelectorAnsweringSomethingThatIsNotAnAddressFailsOpen() {
        StubHttpClient http = StubHttpClient.of(Map.of());
        try (InternetData client = InternetData.builder().httpClient(http).build()) {
            Core<String> core = new Core<>(new Options<String>().client(client)
                    .blockCondition(Map.of("is_vpn", true)), request -> "example.com");

            Lookup found = core.evaluate("request");
            assertFalse(found.isBlocked());
            assertEquals(ErrorKind.BAD_REQUEST, found.error().orElseThrow().kind());
            assertEquals(0, http.calls.size());
        }
    }

    // An upper bound is met by a zero, so only it tells an absent member from its zero value:
    // Unlimited's answer carries no latitude and no resproxy.
    @Test
    void anUpperBoundOnAMemberTheAnswerDoesNotCarryNeverMatches() throws IOException {
        JsonNode unlimited = answer("unlimited-answer");
        Result result;
        try (InternetData client = serving(unlimited)) {
            result = client.lookup(unlimited.get("ip").asText());
        }
        for (Map<String, Object> condition : List.<Map<String, Object>>of(
                Map.of("location", Map.of("latitude", Bound.lt(90))),
                Map.of("resproxy", Map.of("hits", Bound.lte(7))),
                Map.of("is_hosting", List.of(true, false)))) {
            assertFalse(Condition.matches(List.of(condition), result), condition.toString());
        }
    }

    // The generator starts a model's optional list empty, so a detail object served as {} comes
    // back holding one, and it is still the empty object a false flag carries: no plan gap.
    @Test
    void aDetailObjectServedEmptyHasNoPlanGapInside() throws IOException {
        ObjectNode body = (ObjectNode) answer("paid-full-shape").deepCopy();
        body.put("is_pubproxy", false);
        body.putObject("pubproxy");
        body.put("is_bittorrent", false);
        body.putObject("bittorrent");
        Result result;
        try (InternetData client = serving(body)) {
            result = client.lookup(body.get("ip").asText());
        }
        List<Map<String, Object>> conditions = List.of(Map.of("pubproxy", Map.of("exit", true)),
                Map.of("bittorrent", Map.of("last_seen", "2026-11-13")));

        assertFalse(Condition.matches(conditions, result));
        assertEquals(List.of(), Condition.missingMembers(conditions, result));
    }

    private static JsonNode answer(String name) {
        for (JsonNode c : lookup) {
            if (name.equals(c.get("name").asText())) {
                return c.get("body");
            }
        }
        throw new AssertionError("no " + name + " in the corpus's lookup cases");
    }

    private static InternetData serving(JsonNode body) throws IOException {
        StubHttpClient http = StubHttpClient.of(Map.of(body.get("ip").asText(),
                StubHttpClient.Route.ok(MAPPER.writeValueAsString(body))));
        return InternetData.builder().httpClient(http).cacheEnabled(false).retries(0).build();
    }

    private static Result resultFor(JsonNode c) throws IOException {
        if (c.has("bogon")) {
            // Answered locally, so this needs no transport and pins the synthesized shape rather
            // than a fixture's idea of it.
            try (InternetData client =
                    InternetData.builder().httpClient(StubHttpClient.of(Map.of())).build()) {
                return client.lookup(c.get("bogon").asText());
            }
        }
        String ip = c.get("body").get("ip").asText();
        StubHttpClient http = StubHttpClient.of(
                Map.of(ip, StubHttpClient.Route.ok(MAPPER.writeValueAsString(c.get("body")))));
        try (InternetData client =
                InternetData.builder().httpClient(http).cacheEnabled(false).retries(0).build()) {
            return client.lookup(ip);
        }
    }

    private static List<Map<String, Object>> toConditions(JsonNode raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw.isArray()) {
            raw.forEach(entry -> out.add(toCondition(entry)));
        } else {
            out.add(toCondition(raw));
        }
        return out;
    }

    private static Map<String, Object> toCondition(JsonNode raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.fields().forEachRemaining(e -> out.put(e.getKey(), toValue(e.getValue())));
        return out;
    }

    private static Object toValue(JsonNode raw) {
        if (raw.isArray()) {
            List<Object> any = new ArrayList<>();
            raw.forEach(entry -> any.add(toValue(entry)));
            return any;
        }
        if (raw.isObject()) {
            if (isBound(raw)) {
                Bound bound = null;
                var names = raw.fieldNames();
                while (names.hasNext()) {
                    String key = names.next();
                    double value = raw.get(key).asDouble();
                    bound = switch (key) {
                        case "gte" -> bound == null ? Bound.gte(value) : bound.andGte(value);
                        case "gt" -> bound == null ? Bound.gt(value) : bound.andGt(value);
                        case "lte" -> bound == null ? Bound.lte(value) : bound.andLte(value);
                        default -> bound == null ? Bound.lt(value) : bound.andLt(value);
                    };
                }
                return bound;
            }
            return toCondition(raw);
        }
        if (raw.isBoolean()) {
            return raw.asBoolean();
        }
        if (raw.isNumber()) {
            return raw.numberValue();
        }
        if (raw.isNull()) {
            return null;
        }
        return raw.asText();
    }

    private static boolean isBound(JsonNode raw) {
        if (raw.isEmpty()) {
            return false;
        }
        var names = raw.fieldNames();
        while (names.hasNext()) {
            if (!BOUND_KEYS.contains(names.next())) {
                return false;
            }
        }
        return true;
    }
}
