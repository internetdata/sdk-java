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

    // The JDK stops a request's clock once the headers arrive, so this one would otherwise wait for
    // the server to give up.
    @Test
    void theTimeoutCoversABodyThatStallsAfterItsHeaders() {
        InternetData client = clientOn("/stall", Duration.ofMillis(300));
        assertTimesOut(() -> client.database().list(), "after 300ms");
    }

    // Where a runtime bounds each read rather than the whole attempt, a full stall proves nothing;
    // this body never stops arriving, one byte at a time.
    @Test
    void theTimeoutCoversABodyThatTricklesIn() {
        InternetData client = clientOn("/trickle", Duration.ofMillis(300));
        assertTimesOut(() -> client.database().list(), "after 300ms");
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

    // /served answers, /stall stops half way through its body and /trickle sends it a byte every
    // 20 ms, each until the test is over; /slow redirects a download to a blob trickled the same way,
    // which does end.
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
