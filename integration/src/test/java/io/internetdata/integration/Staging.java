package io.internetdata.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.internetdata.InternetData;
import io.internetdata.Result;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** The clients the suite shares, the environment they are pointed at, and the lookup fixtures. */
final class Staging {
    private Staging() {}

    /**
     * Overridable so the same suite can be pointed at another deployment, but staging by default:
     * the license behind the CI key is provisioned there, and a run against production would move
     * real bytes for a credential CI holds.
     */
    static final String BASE_URL = envOr("INTERNETDATA_BASE_URL", "https://api-staging.internetdata.io");

    static final String HOST = URI.create(BASE_URL).getHost();

    /** Where the OAuth calls go for {@link #BASE_URL}: the API's apex, which issues the tokens. */
    static final String ISSUER = envOr("INTERNETDATA_ISSUER", "https://staging.internetdata.io");

    /** Where the keyless lookups are asked as well, since a lookup without a key costs nothing. */
    static final String PRODUCTION = "https://api.internetdata.io";

    /** A stable hosting address, and the one the README teaches. */
    static final String PROBE = "146.70.116.130";

    /** The members every plan answers, which are never the subject of an absent-versus-false question. */
    static final Set<String> EVERY_PLAN = Set.of("ip", "is_bogon", "is_tor", "is_relay", "is_anycast",
            "is_carrier", "is_satellite", "is_ssh", "is_smtp", "is_web", "is_dns_resolver", "is_bittorrent",
            "tor", "relay", "location", "asn", "company", "hostname", "abuse");

    /** The wire name of every member a plan may leave out, mapped to the accessor the client puts it on. */
    static final Map<String, Function<Result, Optional<?>>> ACCESSORS = accessors();

    /** The client, and the recorder underneath it. */
    record Probe(InternetData client, RecordingHttpClient recorder) {}

    /** One lookup's answer, alongside what the wire actually carried. */
    record Answer(String baseUrl, Result result, Map<String, Object> raw, boolean carriedKey) {}

    private static final Map<String, Answer> ANSWERS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static Probe probe;

    /**
     * Why the keyed tests cannot run, or null. Empty counts as absent: Actions interpolates a
     * secret that does not exist to an empty string, and an empty key is sent as no key at all.
     */
    static String skipReason() {
        return key().isEmpty()
                ? "INTERNETDATA_STAGING_KEY is not set; every database endpoint here is licensed"
                : null;
    }

    /** One keyed client for the database tests, so they share a connection pool rather than a key. */
    static Probe probe() {
        if (probe == null) {
            probe = clientFor(BASE_URL, key());
        }
        return probe;
    }

    static InternetData client() {
        return probe().client();
    }

    /** A fresh client on {@code baseUrl}, keyed when {@code key} is not empty. */
    static Probe clientFor(String baseUrl, String key) {
        RecordingHttpClient recorder = new RecordingHttpClient(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), key);
        InternetData.Builder builder = InternetData.builder().baseUrl(baseUrl).httpClient(recorder);
        if (!key.isEmpty()) {
            builder.apiKey(key);
        }
        return new Probe(builder.build(), recorder);
    }

    static Probe keyedClient() {
        return clientFor(BASE_URL, key());
    }

    /** The keyless answer for {@link #PROBE} on {@code baseUrl}, one lookup for the whole run. */
    static Answer keyless(String baseUrl) {
        return ANSWERS.computeIfAbsent("keyless " + baseUrl, ignored -> answerOn(clientFor(baseUrl, ""), baseUrl));
    }

    /**
     * The CI key's answer for {@link #PROBE} on staging, one lookup for the whole run, since each
     * spends one of the organization's lookups.
     */
    static Answer keyed() {
        return ANSWERS.computeIfAbsent("keyed", ignored -> {
            Answer answer = answerOn(keyedClient(), BASE_URL);
            // Checked HERE rather than in one test, so no comparison anywhere can be made against an
            // answer that silently ran unauthenticated: it would answer the keyless shape, which
            // satisfies every containment check vacuously.
            if (!answer.carriedKey()) {
                fail("the key never reached the wire");
            }
            return answer;
        });
    }

    private static Answer answerOn(Probe probe, String baseUrl) {
        Result result = probe.client().lookup(PROBE);
        return new Answer(baseUrl, result, jsonBody(probe.recorder(), "/" + PROBE), probe.recorder().carriedKey());
    }

    /**
     * Holds on every plan: presence is the plan, the value is the answer.
     *
     * <p>Read off the wire, then cross-checked against the typed accessor, which is both halves of
     * the absent-versus-false contract at once: a member the plan includes must survive the mapping,
     * {@code false} and all, and one it does not must read as empty rather than as a plausible
     * {@code false}. Inside the objects every plan answers, the members decoded are exactly the
     * members served.
     */
    static void assertShape(Answer answer) {
        Map<String, Object> raw = answer.raw();
        Result result = answer.result();
        String where = answer.baseUrl();

        for (String member : EVERY_PLAN) {
            assertTrue(raw.containsKey(member), where + ": " + member + " is on every plan");
        }
        assertEquals(PROBE, result.ip());
        assertFalse(result.isBogon(), where + ": a served answer is not a bogon");
        ACCESSORS.forEach((member, accessor) -> {
            Optional<?> held = accessor.apply(result);
            if (raw.containsKey(member)) {
                assertTrue(held.isPresent(), where + ": " + member + " is served and the client dropped it");
            } else {
                assertTrue(held.isEmpty(),
                        where + ": " + member + " is not in this plan, so it must read as empty");
            }
        });
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("location", result.location());
        shared.put("asn", result.asn());
        shared.put("company", result.company());
        shared.put("abuse", result.abuse());
        shared.forEach((member, decoded) -> assertEquals(membersOf(raw.get(member)), membersOf(decoded),
                where + ": " + member + " decoded other members than were served"));
    }

    /** The members an answer or a model carries, null ones left out. */
    static Set<String> membersOf(Object value) {
        Object tree = value instanceof Map<?, ?> ? value : MAPPER.convertValue(value, Map.class);
        Set<String> out = new TreeSet<>();
        ((Map<?, ?>) tree).forEach((member, held) -> {
            if (held != null) {
                out.add((String) member);
            }
        });
        return out;
    }

    /** The captured answer to one path, as a map of exactly what the wire carried. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> jsonBody(RecordingHttpClient recorder, String path) {
        byte[] raw = recorder.body(path)
                .orElseThrow(() -> new AssertionError("no JSON answer was captured for " + path));
        try {
            return MAPPER.readValue(raw, Map.class);
        } catch (IOException e) {
            throw new AssertionError("parsing the answer to " + path, e);
        }
    }

    private static Map<String, Function<Result, Optional<?>>> accessors() {
        Map<String, Function<Result, Optional<?>>> map = new LinkedHashMap<>();
        map.put("is_vpn", Result::isVpn);
        map.put("is_resproxy", Result::isResproxy);
        map.put("is_dcproxy", Result::isDcproxy);
        map.put("is_mobproxy", Result::isMobproxy);
        map.put("is_pubproxy", Result::isPubproxy);
        map.put("is_hosting", Result::isHosting);
        map.put("is_cdn", Result::isCdn);
        map.put("vpn", Result::vpn);
        map.put("resproxy", Result::resproxy);
        map.put("dcproxy", Result::dcproxy);
        map.put("mobproxy", Result::mobproxy);
        map.put("pubproxy", Result::pubproxy);
        map.put("hosting", Result::hosting);
        map.put("cdn", Result::cdn);
        map.put("carrier", Result::carrier);
        map.put("satellite", Result::satellite);
        map.put("ssh", Result::ssh);
        map.put("smtp", Result::smtp);
        map.put("web", Result::web);
        map.put("dns_resolver", Result::dnsResolver);
        map.put("bittorrent", Result::bittorrent);
        map.put("whois", Result::whois);
        map.put("rwhois", Result::rwhois);
        map.put("hosted_domains", Result::hostedDomains);
        map.put("trial_expired", Result::trialExpired);
        return Map.copyOf(map);
    }

    private static String key() {
        return envOr("INTERNETDATA_STAGING_KEY", "");
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static List<String> keylessHosts() {
        return List.of(BASE_URL, PRODUCTION);
    }
}
