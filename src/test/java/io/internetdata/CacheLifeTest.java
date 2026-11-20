package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * How long a cached answer lives. An answer whose trial_expired or cap_reached is true is the
 * reduced one only until the trial resets or the cap is raised, so it is kept a minute at most, or
 * the client's TTL when that is shorter; every other answer keeps the TTL. Each case is measured on
 * the cache's own clock, moved by hand: the answer is still cached a nanosecond before its life
 * ends, so a repeat sends nothing, and gone when it ends, so a repeat asks the API again.
 */
class CacheLifeTest {
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration MINUTE = Duration.ofSeconds(60);

    private record Case(String name, String extra, Duration ttl, Duration life) {}

    private static final List<Case> CASES = List.of(
            new Case("trial_expired true, the default TTL", "\"trial_expired\": true", null, MINUTE),
            new Case("cap_reached true, the default TTL", "\"cap_reached\": true", null, MINUTE),
            new Case("both true, the default TTL", "\"trial_expired\": true, \"cap_reached\": true", null, MINUTE),
            new Case("trial_expired true, a 30 s TTL", "\"trial_expired\": true", Duration.ofSeconds(30),
                    Duration.ofSeconds(30)),
            new Case("cap_reached true, a 30 s TTL", "\"cap_reached\": true", Duration.ofSeconds(30),
                    Duration.ofSeconds(30)),
            new Case("trial_expired true, a 2 h TTL", "\"trial_expired\": true", Duration.ofHours(2), MINUTE),
            new Case("trial_expired false, the default TTL", "\"trial_expired\": false", null, HOUR),
            new Case("cap_reached false, the default TTL", "\"cap_reached\": false", null, HOUR),
            new Case("neither flag, the default TTL", null, null, HOUR),
            new Case("neither flag, a 30 s TTL", null, Duration.ofSeconds(30), Duration.ofSeconds(30)));

    @Test
    void aLookupsAnswerLivesAsLongAsItsFlagsAllow() {
        for (Case c : CASES) {
            StubHttpClient http = StubHttpClient.responding(
                    ip -> StubHttpClient.Route.ok(StubHttpClient.answer(ip, c.extra())));
            ManualTicker clock = new ManualTicker();
            try (InternetData client = clientOn(http, clock, c.ttl())) {
                client.lookup("146.70.116.130");
                assertEquals(1, http.calls.size(), c.name() + ": the first lookup");

                clock.advance(c.life().minusNanos(1));
                client.lookup("146.70.116.130");
                assertEquals(1, http.calls.size(), c.name() + ": still cached a nanosecond before "
                        + c.life() + ", so a repeat sends nothing");

                clock.advance(Duration.ofNanos(1));
                client.lookup("146.70.116.130");
                assertEquals(2, http.calls.size(), c.name() + ": gone at " + c.life()
                        + ", so a repeat asks the API again");
            }
        }
    }

    @Test
    void aBatchEntryLivesAsLongAsItsFlagsAllow() {
        for (Case c : CASES) {
            StubHttpClient http = StubHttpClient.responding(
                    ip -> StubHttpClient.Route.ok(StubHttpClient.answer(ip, c.extra())));
            ManualTicker clock = new ManualTicker();
            try (InternetData client = clientOn(http, clock, c.ttl())) {
                List<String> ips = List.of("146.70.116.130", "146.70.116.131");
                client.lookupBatch(ips);
                assertEquals(ips, http.batchIps, c.name() + ": the first batch sends both");

                clock.advance(c.life().minusNanos(1));
                client.lookupBatch(ips);
                client.lookup("146.70.116.130");
                assertEquals(1, http.calls.size(), c.name() + ": both still cached a nanosecond before "
                        + c.life() + ", for a batch and a lookup alike");

                clock.advance(Duration.ofNanos(1));
                client.lookupBatch(ips);
                assertEquals(List.of("146.70.116.130", "146.70.116.131", "146.70.116.130", "146.70.116.131"),
                        http.batchIps, c.name() + ": both gone at " + c.life() + ", so the batch sends them again");
            }
        }
    }

    private static InternetData clientOn(StubHttpClient http, ManualTicker clock, Duration ttl) {
        InternetData.Builder b = InternetData.builder().apiKey("test-key").httpClient(http).cacheTicker(clock);
        if (ttl != null) {
            b.cacheTtl(ttl);
        }
        return b.build();
    }

    /** A clock that moves only when a test moves it. */
    private static final class ManualTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }
}
