package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.internetdata.model.Database;
import io.internetdata.model.DatabaseMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

/**
 * Hits the real API, so it is opt-in:
 * {@code INTERNETDATA_LIVE=1 INTERNETDATA_API_KEY=... mvn test}.
 *
 * <p>CI leaves it off. Every endpoint here is licensed, so there is nothing an unauthenticated run
 * could assert, and a key in a pull-request workflow is a key in a public log.
 *
 * <p>Nothing is DOWNLOADED here on purpose: metadata is what says how big a database is, and a
 * suite that transfers before it reads that can pull gigabytes from one mistyped id. The
 * integration suite does the real transfer, against a key licensed only for the smallest databases.
 */
@EnabledIfEnvironmentVariable(named = "INTERNETDATA_LIVE", matches = "1")
class LiveTest {
    @Test
    void theCatalogAnswersTheFamilyShape() {
        InternetData client = InternetData.create(System.getenv("INTERNETDATA_API_KEY"));

        List<Database> families = client.database().list();
        assertFalse(families.isEmpty(), "the catalog came back empty");

        for (Database family : families) {
            assertNotNull(family.getBase(), "a family arrived with no base");
            assertNotNull(family.getName(), family.getBase() + " carries no name");
            assertNotNull(family.getStanding(), family.getBase() + " carries no standing");
            family.getVersions().forEach(version -> {
                assertNotNull(version.getId(), family.getBase() + " has a version with no id");
                assertTrue(version.getVersion() > 0, version.getId() + " has no version number");
                assertFalse(version.getFormats().isEmpty(), version.getId() + " carries no formats");
            });
        }

        Database licensed = families.stream()
                .filter(f -> f.getStanding() == Database.StandingEnum.LICENSED)
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
