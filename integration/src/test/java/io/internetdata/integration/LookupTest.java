package io.internetdata.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.internetdata.AsnResult;
import io.internetdata.BatchResult;
import io.internetdata.Bogon;
import io.internetdata.DomainResult;
import io.internetdata.ErrorKind;
import io.internetdata.InternetDataException;
import io.internetdata.Result;
import io.internetdata.integration.Staging.Answer;
import io.internetdata.integration.Staging.Probe;
import io.internetdata.model.Entitlement;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The published library's lookups, without a key against staging and production, and with the CI
 * key against staging.
 *
 * <p>Nothing here pins a field COUNT or which plan answers which member. A plan is asserted as a
 * RELATION, a keyed answer serving a superset of the keyless one, so a pricing change stays a
 * pricing change instead of arriving as a red SDK build. The CI key's organization is on the free
 * plan, whose first 100 lookups a UTC day answer every field and the rest Unlimited's, so the keyed
 * tests ask as few as they can.
 */
class LookupTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.internetdata.integration.Staging#keylessHosts")
    void aLookupWithoutAKeyKeepsTheShape(String baseUrl) {
        Answer answer = Staging.keyless(baseUrl);

        assertFalse(answer.carriedKey(), baseUrl + ": a client without a key sent one");
        Staging.assertShape(answer);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.internetdata.integration.Staging#keylessHosts")
    void myIpWithoutAKeyLooksUpTheCallingAddress(String baseUrl) {
        Probe probe = Staging.clientFor(baseUrl, "");

        Result mine = probe.client().myIp();

        assertFalse(mine.ip().isEmpty(), baseUrl + ": no address came back");
        assertEquals(Staging.membersOf(Staging.jsonBody(probe.recorder(), "/myip")), Staging.membersOf(mine.raw()),
                baseUrl + ": the answer decoded other members than were served");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.internetdata.integration.Staging#keylessHosts")
    void anAsAndADomainWithoutAKeyAreAnswered(String baseUrl) {
        Probe probe = Staging.clientFor(baseUrl, "");

        AsnResult asn = probe.client().lookupAsn(15169);
        DomainResult domain = probe.client().lookupDomain("example.com");

        assertEquals(15169, asn.asn());
        assertFalse(asn.name().isEmpty(), baseUrl + ": AS15169 has no name");
        assertEquals("example.com", domain.domain());
        assertEquals(Staging.membersOf(Staging.jsonBody(probe.recorder(), "/asn/15169")),
                Staging.membersOf(asn.raw()), baseUrl + ": the AS answer decoded other members than were served");
        assertEquals(Staging.membersOf(Staging.jsonBody(probe.recorder(), "/domain/example.com")),
                Staging.membersOf(domain.raw()),
                baseUrl + ": the domain answer decoded other members than were served");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.internetdata.integration.Staging#keylessHosts")
    void aBatchWithoutAKeyIsRefusedOnceAndItsBogonStillAnswered(String baseUrl) {
        Probe probe = Staging.clientFor(baseUrl, "");

        LinkedHashMap<String, BatchResult> got = probe.client().lookupBatch(
                // Two addresses on the wire: a keyless batch of one is answered as its lookup.
                List.of(Staging.PROBE, "8.8.8.8", "10.0.0.1"));

        assertTrue(got.get("10.0.0.1").orElseThrow().isBogon());
        InternetDataException refused = got.get(Staging.PROBE).error()
                .orElseThrow(() -> new AssertionError("a batch without a key must fail"));
        assertEquals(ErrorKind.UNAUTHORIZED, refused.kind());
        assertEquals(Optional.of(401), refused.statusCode());
        assertFalse(refused.retryable());
        assertEquals(List.of("/batch"), probe.recorder().facts().stream().map(fact -> fact.path()).toList(),
                "one request, never retried");
    }

    @Test
    void aBogonIsAnsweredWithoutTouchingTheNetwork() {
        Probe probe = Staging.clientFor(Staging.BASE_URL, "");

        Result result = probe.client().lookup("10.0.0.1");

        assertEquals(List.of(), probe.recorder().facts(), "the bogon path reached the network");
        assertTrue(result.isBogon());
        assertTrue(Bogon.isBogon("10.0.0.1"), "the standalone form must agree");
        // A bogon answers the WIDEST shape whatever the plan: every flag present and false, every
        // detail object present and empty. It is the one answer nobody should read a plan from.
        Staging.ACCESSORS.forEach((member, accessor) -> {
            if (member.equals("trial_expired")) {
                return;
            }
            Optional<?> held = accessor.apply(result);
            assertTrue(held.isPresent(), "a bogon answers every member, and " + member + " is missing");
            if (member.startsWith("is_")) {
                assertEquals(false, held.get(), "a bogon must answer " + member + " false, not empty");
            }
        });
    }

    @Test
    void aKeyReachesTheWireAndItsAnswerKeepsTheShape() {
        skipWithoutAKey();
        Answer answer = Staging.keyed();

        assertTrue(answer.carriedKey(), "the key never reached the wire");
        Staging.assertShape(answer);
        Object expired = answer.raw().get("trial_expired");
        if (expired != null) {
            // A free-plan key's first 100 lookups of the UTC day are answered in full, and the rest
            // as Unlimited's: which half a run sees depends on the day.
            assertInstanceOf(Boolean.class, expired, "trial_expired is a flag");
            assertEquals(!(Boolean) expired, answer.raw().containsKey("is_vpn"),
                    "is_vpn is served exactly while the day's trial lasts");
        }
    }

    @Test
    void aKeyedAnswerServesASupersetOfTheKeylessOneAndAbsentIsNeverFalse() {
        skipWithoutAKey();
        Answer keyed = Staging.keyed();
        Answer keyless = Staging.keyless(Staging.BASE_URL);

        Set<String> served = new LinkedHashSet<>(keyed.raw().keySet());
        served.remove("trial_expired");
        Set<String> free = new LinkedHashSet<>(keyless.raw().keySet());
        for (String member : free) {
            assertTrue(served.contains(member), "the key's answer drops " + member + ", which Unlimited serves");
        }
        int compared = 0;
        for (String member : served) {
            if (!free.contains(member) && Staging.ACCESSORS.containsKey(member)) {
                assertTrue(Staging.ACCESSORS.get(member).apply(keyless.result()).isEmpty(),
                        member + " is not on Unlimited, so it must read as empty");
                compared++;
            }
        }
        // A spent trial answers Unlimited's members, which makes the comparison a no-op. Say so
        // rather than passing on nothing.
        Assumptions.assumeTrue(compared > 0, "the key's answer serves no member Unlimited lacks today");
    }

    @Test
    void aFreePlanKeysBatchOfTwoIsRefusedAsABadRequestOnce() {
        skipWithoutAKey();
        Assumptions.assumeTrue(Staging.keyed().raw().containsKey("trial_expired"),
                "the CI key is not on the free plan, which alone refuses a batch of two");
        Probe probe = Staging.keyedClient();

        LinkedHashMap<String, BatchResult> got = probe.client().lookupBatch(List.of(Staging.PROBE, "8.8.8.8"));

        for (String ip : List.of(Staging.PROBE, "8.8.8.8")) {
            InternetDataException refused = got.get(ip).error()
                    .orElseThrow(() -> new AssertionError(ip + ": a free-plan key's batch of two must fail"));
            assertEquals(ErrorKind.BAD_REQUEST, refused.kind());
            assertEquals(Optional.of(402), refused.statusCode());
            assertFalse(refused.retryable());
        }
        assertEquals(List.of("/batch"), probe.recorder().facts().stream().map(fact -> fact.path()).toList(),
                "one request, never retried");
        assertTrue(probe.recorder().carriedKey(), "the key never reached the wire");
    }

    @Test
    void aKeysBatchOfOneIsAnswered() {
        skipWithoutAKey();
        Probe probe = Staging.keyedClient();

        LinkedHashMap<String, BatchResult> got = probe.client().lookupBatch(List.of(Staging.PROBE));

        assertTrue(got.get(Staging.PROBE).isSuccess(), "a batch of one failed: " + got.get(Staging.PROBE));
        assertTrue(probe.recorder().carriedKey(), "the key never reached the wire");
        Map<String, Object> served = Staging.jsonBody(probe.recorder(), "/batch");
        Object answer = served.get("results") instanceof Map<?, ?> results ? results.get(Staging.PROBE) : null;
        assertEquals(Staging.membersOf(answer), Staging.membersOf(got.get(Staging.PROBE).orElseThrow().raw()),
                "the batch's answer decoded other members than were served");
    }

    @Test
    void myEntitlementNamesTheKeysPlanAndUsage() {
        skipWithoutAKey();
        Probe probe = Staging.keyedClient();

        Entitlement entitlement = probe.client().myEntitlement();

        assertTrue(probe.recorder().carriedKey(), "the key never reached the wire");
        assertFalse(entitlement.getPlan().getKey().isEmpty(), "the plan has no key");
        assertTrue(entitlement.getUsage().getWindowEnd().isAfter(entitlement.getUsage().getWindowStart()),
                "the allowance window ends before it starts");
        System.out.println("CI key: plan " + entitlement.getPlan().getKey() + ", "
                + entitlement.getUsage().getRequests() + " of " + entitlement.getUsage().getQuota() + " this window");
    }

    private static void skipWithoutAKey() {
        Assumptions.assumeTrue(Staging.skipReason() == null, Staging::skipReason);
    }
}
