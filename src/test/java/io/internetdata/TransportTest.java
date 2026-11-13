package io.internetdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The client's own JDK transport against a real local server. The stubs elsewhere answer whatever
 * body type they like, so only this suite reaches the buffering behind the request timeout.
 */
class TransportTest {
    private static final int BLOB_BYTES = 40;

    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void aServedAnswerAndAnErrorBodyBothArriveWhole() {
        InternetData client = clientOn("/served", Duration.ofSeconds(10));

        assertTrue(client.database().list().isEmpty());
        InternetDataException e = assertThrows(InternetDataException.class,
                () -> client.database().metadata("nope"));
        assertEquals(ErrorKind.BAD_REQUEST, e.kind());
        assertEquals("UNKNOWN_DATASET", e.getMessage());
    }

    @Test
    void aServedLookupAndItsRefusalBothArriveWhole() {
        InternetData client = clientOn("/served", Duration.ofSeconds(10));

        assertEquals("1.1.1.1", client.lookup("1.1.1.1").ip());
        InternetDataException e = assertThrows(InternetDataException.class, () -> client.lookup("2.2.2.2"));
        assertEquals(ErrorKind.FORBIDDEN, e.kind());
        assertEquals("source address not allowed for this API key", e.getMessage());
    }

    /**
     * Every lookup goes to the client's own base URL. The spec gives each lookup path a server of
     * its own, production's API host, which a generated client that honored it would send every
     * lookup to, whatever base URL it was built with.
     */
    @Test
    void everyLookupGoesToTheClientsBaseUrl() {
        InternetData client = clientOn("/served", Duration.ofSeconds(10));

        assertEquals("1.1.1.1", client.lookup("1.1.1.1").ip());
        assertEquals("1.1.1.1", client.myIp().ip());
        assertEquals(9009, client.lookupAsn(9009).asn());
        assertEquals("example.com", client.lookupDomain("example.com").domain());
        assertTrue(client.lookupBatch(java.util.List.of("8.8.8.8", "9.9.9.9")).get("9.9.9.9").isSuccess());
        assertEquals("free", client.myEntitlement().getPlan().getKey());
    }

    // The JDK stops a request's clock once the headers arrive, so this one would otherwise wait for
    // the server to give up.
    @Test
    void theTimeoutCoversABodyThatStallsAfterItsHeaders() {
        InternetData client = clientOn("/stall", Duration.ofMillis(300));
        assertTimesOut(() -> client.database().list(), "after 300ms");
        assertTimesOut(() -> client.lookup("8.8.8.8"), "after 300ms");
    }

    @Test
    void aPerCallTimeoutBelowTheClientsFiresOnTheRealTransport() {
        InternetData client = clientOn("/stall", Duration.ofSeconds(10));
        assertTimesOut(() -> client.lookup("9.9.9.9",
                new LookupOptions().requestTimeout(Duration.ofMillis(300))), "after 300ms");
        assertTimesOut(() -> client.lookup("8.8.8.8",
                new LookupOptions().requestTimeout(Duration.ofMillis(300))), "after 300ms");
    }

    // Where a runtime bounds each read rather than the whole attempt, a full stall proves nothing;
    // this body never stops arriving, one byte at a time.
    @Test
    void theTimeoutCoversABodyThatTricklesIn() {
        InternetData client = clientOn("/trickle", Duration.ofMillis(300));
        assertTimesOut(() -> client.database().list(), "after 300ms");
        assertTimesOut(() -> client.lookup("7.7.7.7"), "after 300ms");
    }

    // The bound is on an API call. A database transfer that takes longer than it still completes.
    @Test
    void aTransferOutlastingTheTimeoutStillArrives() {
        InternetData client = clientOn("/slow", Duration.ofMillis(300));

        long started = System.nanoTime();
        byte[] bytes = client.database().downloadBytes("bogon_asn_v1", DatabaseFormat.CSVGZ);
        long took = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(BLOB_BYTES, bytes.length);
        assertTrue(took > 300, "took " + took + "ms, so the transfer never outlasted the bound");
    }

    private InternetData clientOn(String prefix, Duration timeout) {
        return InternetData.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + prefix)
                .retries(0)
                .cacheEnabled(false)
                .requestTimeout(timeout)
                .build();
    }

    private static void assertTimesOut(Runnable call, String bound) {
        long started = System.nanoTime();
        InternetDataException e = assertThrows(InternetDataException.class, call::run);
        long took = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(ErrorKind.NETWORK, e.kind());
        assertTrue(e.retryable());
        assertTrue(e.getMessage().contains(bound), e.getMessage());
        assertTrue(took < 5000, "took " + took + "ms, past the bound");
    }

    // /served answers, /stall stops half way through its body, or before its headers for 9.9.9.9, and
    // /trickle sends it a byte every 20 ms, each until the test is over; /slow redirects a download
    // to a blob trickled the same way, which does end.
    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        exchange.getResponseHeaders().add("content-type", "application/json");
        OutputStream out = exchange.getResponseBody();
        try {
            switch (path) {
                case "/served/api/v2/database/list":
                    write(exchange, out, 200, "{\"databases\": []}");
                    break;
                case "/served/api/v2/database/metadata":
                    write(exchange, out, 404, "{\"rc\": \"UNKNOWN_DATASET\"}");
                    break;
                case "/served/1.1.1.1":
                    write(exchange, out, 200, StubHttpClient.answer("1.1.1.1"));
                    break;
                case "/served/2.2.2.2":
                    write(exchange, out, 403, "{\"error\": \"source address not allowed for this API key\"}");
                    break;
                case "/served/myip":
                    write(exchange, out, 200, StubHttpClient.answer("1.1.1.1"));
                    break;
                case "/served/asn/9009":
                    write(exchange, out, 200, "{\"asn\": 9009, \"name\": \"M247 Europe SRL\", \"country\": \"RO\","
                            + " \"registry\": \"ripe\", \"allocated\": \"2005-06-06\", \"domain\": \"m247global.com\"}");
                    break;
                case "/served/domain/example.com":
                    write(exchange, out, 200, "{\"domain\": \"example.com\", \"is_registered\": true, \"has_a\": true,"
                            + " \"has_aaaa\": true, \"has_mx\": true, \"has_ns\": true, \"has_cname\": false}");
                    break;
                case "/served/batch":
                    write(exchange, out, 200, "{\"results\": {\"8.8.8.8\": " + StubHttpClient.answer("8.8.8.8")
                            + ", \"9.9.9.9\": " + StubHttpClient.answer("9.9.9.9") + "}, \"errors\": {}}");
                    break;
                case "/served/api/v1/entitlement":
                    write(exchange, out, 200, "{\"org_id\": \"6f1c2a52-6c83-4c8a-9a3e-2f3b8c1d0e9f\", \"apikey\":"
                            + " {\"id\": \"0b6e2d8a-1c4f-4a5e-8d7b-3e2f1a0c9b8d\", \"expires\": null,"
                            + " \"allowed_cidrs\": []}, \"plan\": {\"key\": \"free\", \"tier\": \"free\"},"
                            + " \"usage\": {\"requests\": 1, \"quota\": 100, \"hard_limit\": null,"
                            + " \"window_start\": \"2026-11-13T00:00:00Z\", \"window_end\": \"2026-11-14T00:00:00Z\"}}");
                    break;
                case "/stall/9.9.9.9":
                    stall();
                    break;
                case "/stall/8.8.8.8":
                    exchange.sendResponseHeaders(200, 100);
                    out.write("{\"ip\": ".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    stall();
                    break;
                case "/trickle/7.7.7.7":
                    exchange.sendResponseHeaders(200, 400);
                    trickle(out, 400);
                    break;
                case "/stall/api/v2/database/list":
                    exchange.sendResponseHeaders(200, 100);
                    out.write("{\"databases\": ".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    stall();
                    break;
                case "/trickle/api/v2/database/list":
                    exchange.sendResponseHeaders(200, 400);
                    trickle(out, 400);
                    break;
                case "/slow/api/v2/database/download":
                    exchange.getResponseHeaders().add("Location",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/blob");
                    exchange.sendResponseHeaders(302, -1);
                    break;
                case "/blob":
                    exchange.sendResponseHeaders(200, BLOB_BYTES);
                    trickle(out, BLOB_BYTES);
                    break;
                default:
                    write(exchange, out, 404, "{\"rc\": \"NOT_FOUND\"}");
                    break;
            }
        } finally {
            // Unlike the body stream's own close, this one does not throw over a short body.
            exchange.close();
        }
    }

    private static void write(HttpExchange exchange, OutputStream out, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        out.write(bytes);
    }

    // A byte every 20 ms, so no single read waits long.
    private void trickle(OutputStream out, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) {
            out.write(' ');
            out.flush();
            try {
                if (release.await(20, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void stall() {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
