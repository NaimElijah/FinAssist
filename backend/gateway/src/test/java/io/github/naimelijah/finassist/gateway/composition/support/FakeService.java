package io.github.naimelijah.finassist.gateway.composition.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for the ledger or the assistant: a real local HTTP server that records the last
 * request it received and answers with a response the test controls.
 *
 * <p>Used by {@code GatewayLoginFlowIT} to prove what the gateway's relay actually sends
 * downstream — the stripped path, the {@code Authorization} header it adds, and the {@code
 * Cookie} and CSRF headers it removes — and to prove that this gateway passes a downstream
 * problem+json response through unchanged rather than rewriting it.
 *
 * <p>Not thread-safe beyond what a single sequential test needs: {@link #lastRequest()} reads a
 * single slot that the next request overwrites, exactly like the fixture it stands in for is used
 * — one relay in flight at a time.
 *
 * <p>{@link #stall()} makes it accept a request and then never answer, until {@link
 * #releaseStalledRequests()}: the upstream a relay's read timeout exists for. Each exchange runs on
 * its own thread, so a stalled one holds nothing else up.
 */
public final class FakeService implements AutoCloseable {

    /**
     * How long a stalled exchange waits before giving up on its own: far beyond any budget a test
     * asserts, but finite, so a test that forgets {@link #releaseStalledRequests()} leaks a thread
     * for this long rather than for ever. The same limit as {@code FakeOpenIdProvider}'s.
     */
    private static final Duration STALL_LIMIT = Duration.ofMinutes(2);

    private final HttpServer server;
    private final AtomicReference<ReceivedRequest> lastRequest = new AtomicReference<>();
    private volatile CannedResponse nextResponse = CannedResponse.ok();
    private final ExecutorService exchanges = Executors.newCachedThreadPool();
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicInteger stalledArrivals = new AtomicInteger();
    private volatile boolean stalling;

    /** Starts a server on a loopback port the OS assigns, so tests never collide over one. */
    public FakeService() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(exchanges);
        this.server.start();
    }

    /** The base URL a route's {@code uri} can target: {@code http://localhost:<port>}. */
    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** What the most recent request carried, or {@code null} if none has arrived yet. */
    public ReceivedRequest lastRequest() {
        return lastRequest.get();
    }

    /** Replaces the response the next request receives. Stays in effect until changed again. */
    public void respondNext(int status, String contentType, String body) {
        this.nextResponse = new CannedResponse(status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Forgets the last request, so that {@link #lastRequest()} being {@code null} means "nothing
     * reached this service" rather than "nothing has reached it since the JVM started".
     *
     * <p>This fixture is started once per test class, so without it a test asserting that the
     * gateway refused a request before relaying it would be reading whatever an earlier test
     * happened to send.
     */
    public void forgetLastRequest() {
        lastRequest.set(null);
    }

    /** Every subsequent request is accepted and then left without a status line, until released. */
    public void stall() {
        stalling = true;
    }

    /** Lets every stalled exchange finish and stops stalling new ones. Idempotent. */
    public void releaseStalledRequests() {
        stalling = false;
        stalledArrivals.set(0);
        release.getAndSet(new CountDownLatch(1)).countDown();
    }

    /**
     * How many requests have arrived while stalled since the last release, so a test can tell a
     * bounded relay giving up from a flow that never reached this service at all.
     */
    public int stalledArrivals() {
        return stalledArrivals.get();
    }

    @Override
    public void close() {
        releaseStalledRequests();
        server.stop(0);
        exchanges.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (stalling) {
            stalledArrivals.incrementAndGet();
            awaitRelease();
            exchange.close();
            return;
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
        lastRequest.set(new ReceivedRequest(
                exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), Map.copyOf(headers)));
        exchange.getRequestBody().readAllBytes();

        CannedResponse response = this.nextResponse;
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    private void awaitRelease() throws IOException {
        try {
            release.get().await(STALL_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("the stalled exchange was interrupted", interrupted);
        }
    }

    /** What this service actually received: the path with any prefix already stripped, and the headers that survived the relay. */
    public record ReceivedRequest(String method, String path, Map<String, List<String>> headers) {

        /** Case-insensitive presence check, matching HTTP's own header semantics. */
        public boolean hasHeader(String name) {
            return headers.containsKey(name.toLowerCase(Locale.ROOT));
        }

        /** The first value of a header this service received, or {@code null} if it was not sent. */
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return (values == null || values.isEmpty()) ? null : values.get(0);
        }
    }

    private record CannedResponse(int status, String contentType, byte[] body) {
        static CannedResponse ok() {
            return new CannedResponse(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
