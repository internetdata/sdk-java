package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.internetdata.model.Database;
import io.internetdata.model.DatabaseMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

/**
 * Hits the real API, so it is opt-in:
 * {@code INTERNETDATA_LIVE=1 INTERNETDATA_API_KEY=... mvn test}; the lookups run without a key.
 *
 * <p>CI leaves it off. A test suite that queries the real API on every pull request eventually
 * fails for reasons that have nothing to do with the change under review, and a key in a
 * pull-request workflow is a key in a public log.
 *
 * <p>Nothing is DOWNLOADED here on purpose: metadata is what says how big a database is, and a
 * suite that transfers before it reads that can pull gigabytes from one mistyped ID. The
 * integration suite does the real transfer, against a key licensed only for the smallest databases.
 */
@EnabledIfEnvironmentVariable(named = "INTERNETDATA_LIVE", matches = "1")
class LiveTest {
    @Test
    void aLookupWithoutAKeyAnswersUnlimitedsMembersAndWithholdsThePaidOnes() {
        try (InternetData client = InternetData.create()) {
            Result tor = client.lookup("185.220.101.1");
            Result notTor = client.lookup("1.1.1.1");

            System.out.println("185.220.101.1 -> isTor=" + tor.isTor() + " isVpn=" + tor.isVpn()
                    + " raw=" + tor.raw());
            System.out.println("1.1.1.1       -> isTor=" + notTor.isTor() + " isVpn=" + notTor.isVpn()
                    + " isVpn.orElse(false)=" + notTor.isVpn().orElse(false));

            assertTrue(tor.isTor(), "185.220.101.1 is a Tor exit");
            assertFalse(notTor.isTor(), "1.1.1.1 is not");
            // Absent, not false: these need a paid plan and this call carries no key.
            assertTrue(notTor.isVpn().isEmpty(), "an answer without a key must withhold is_vpn");
            assertTrue(notTor.web().isEmpty(), "an answer without a key must withhold web");
            assertNull(notTor.location().getCity(), "an answer without a key must withhold the city");
            assertFalse(notTor.location().getCountry().isEmpty(), "every plan answers the country");

            Result bogon = client.lookup("192.168.1.1");
            assertTrue(bogon.isBogon());
            assertEquals("192.168.1.1", bogon.ip());

            AsnResult asn = client.lookupAsn(15169);
            assertEquals(15169, asn.asn());
            assertTrue(asn.prefixes().isEmpty(), "an AS answer without a key must withhold its prefixes");

            DomainResult domain = client.lookupDomain("example.com");
            assertEquals("example.com", domain.domain());
            assertTrue(domain.dns().isEmpty(), "a domain answer without a key must withhold its records");
        }
    }

    @Test
    void theCatalogAnswersTheFamilyShape() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("INTERNETDATA_API_KEY") != null,
                "INTERNETDATA_API_KEY is not set; every database endpoint here is licensed");
        InternetData client = InternetData.create(System.getenv("INTERNETDATA_API_KEY"));

        List<Database> families = client.database().list();
        assertFalse(families.isEmpty(), "the catalog came back empty");

        for (Database family : families) {
            assertNotNull(family.getBase(), "a family arrived with no base");
            assertNotNull(family.getName(), family.getBase() + " carries no name");
            assertNotNull(family.getStanding(), family.getBase() + " carries no standing");
            family.getVersions().forEach(version -> {
                assertNotNull(version.getId(), family.getBase() + " has a version with no ID");
                assertTrue(version.getVersion() > 0, version.getId() + " has no version number");
                assertFalse(version.getFormats().isEmpty(), version.getId() + " carries no formats");
            });
        }

        Database licensed = families.stream()
                .filter(f -> f.getStanding() == io.internetdata.model.Standing.LICENSED)
                .filter(f -> !f.getVersions().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("this key licenses nothing to inspect"));
        String id = licensed.getVersions().get(0).getId();

        DatabaseMetadata meta = client.database().metadata(id);
        assertEquals(id, meta.getId());
        assertNotNull(meta.getUpdated(), id + " publishes no build date");
        assertFalse(meta.getSize().isEmpty(), id + " publishes no size");
        System.out.println(id + ": updated " + meta.getUpdated()
                + ", " + meta.getEntries() + " entries, sizes " + meta.getSize());
    }
}
