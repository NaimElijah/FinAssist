package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The gateway as {@code package} built it: the repackaged jar that {@code backend/gateway/Dockerfile}
 * copies into the image, run exactly as the image runs it, with {@code java -jar}.
 *
 * <p>Every other suite runs on Maven's test classpath, which can hold a library the packaged jar
 * lacks. That is how #233 shipped. snakeyaml, declared at test scope, left the jar, so Boot could
 * not read {@code application.yml} and the gateway could not start anywhere outside a test, while
 * every suite stayed green. So this starts the jar itself and requires its readiness probe to
 * answer, which catches that whole class, whichever library or setting goes missing, rather than
 * the one library that went missing then.
 *
 * <p>The gateway needs nothing running to start: it builds its Keycloak registration by hand and
 * runs no discovery (ADR-030), so a client secret and port 0 are all it is given, in an environment
 * stripped of every {@code GATEWAY_*} and {@code OTEL_*} variable this JVM inherited. Failsafe runs
 * after {@code package}, so the jar exists by the time this does; its path comes from the pom, as
 * {@value #JAR_PROPERTY}.
 */
class GatewayPackagedJarIT {

    private static final String JAR_PROPERTY = "finassist.gateway.jar";

    /** What Spring Boot logs once the application context is up and the server is listening. */
    private static final String STARTED_LINE = "Started GatewayApplication";

    /**
     * Tomcat's line naming the port it bound. The gateway is started on port 0 and this is read
     * rather than a port reserved here and released, which another process could take in between
     * (the race #238 recorded for a relay test).
     */
    private static final Pattern BOUND_PORT_LINE = Pattern.compile("Tomcat started on port (\\d+)");

    /** The prefixes of the variables the gateway and its telemetry read, none of which it may inherit. */
    private static final List<String> INHERITED_PREFIXES_REMOVED = List.of("GATEWAY_", "OTEL_");

    private static final String READINESS_PATH = "/actuator/health/readiness";

    /** About 11 seconds on the development machine; a CI runner gets generous headroom. */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(90);

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** How much of the gateway's own output a failure quotes, so the reason is in the report. */
    private static final int TAIL_LINES = 15;

    /**
     * A stack frame, or the "... 12 more" that ends a cause. A failed start prints dozens of them
     * after the one line that names the reason, so they are left out of the quoted tail.
     */
    private static final Pattern STACK_FRAME = Pattern.compile("^\\s+(at |\\.\\.\\. \\d+ more)");

    private static final int HTTP_OK = 200;

    @Test
    void Given_the_packaged_jar_When_it_is_run_Then_it_starts_and_reports_ready() throws Exception {
        Deque<String> tail = new ArrayDeque<>();
        AtomicInteger boundPort = new AtomicInteger();
        Process gateway = start(packagedJar());
        try {
            CompletableFuture<Boolean> started =
                    CompletableFuture.supplyAsync(() -> readUntilStarted(gateway, tail, boundPort));
            boolean reachedStarted;
            try {
                reachedStarted = started.get(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError(
                        "the packaged gateway did not start within " + START_TIMEOUT + "; its last output:"
                                + System.lineSeparator() + joined(tail),
                        e);
            }
            assertThat(reachedStarted)
                    .as("the packaged gateway exited before it started; its last output:%n%s", joined(tail))
                    .isTrue();
            int port = boundPort.get();
            assertThat(port)
                    .as("the port Tomcat reported binding; its last output:%n%s", joined(tail))
                    .isPositive();

            HttpResponse<String> readiness = HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .build()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + READINESS_PATH))
                                    .timeout(REQUEST_TIMEOUT)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(readiness.statusCode()).as("GET %s", READINESS_PATH).isEqualTo(HTTP_OK);
        } finally {
            stop(gateway);
        }
    }

    /** Fails loudly rather than starting nothing when the build did not say where the jar is. */
    private static Path packagedJar() {
        String jar = System.getProperty(JAR_PROPERTY);
        if (jar == null) {
            throw new IllegalStateException(JAR_PROPERTY
                    + " is not set. Failsafe sets it in pom.xml; run this test through `./mvnw verify`,"
                    + " after `package`, or pass -D" + JAR_PROPERTY + "=<path to the repackaged jar>.");
        }
        Path path = Path.of(jar);
        assertThat(path).as("the repackaged jar `package` writes").isRegularFile();
        return path;
    }

    private static Process start(Path jar) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", jar.toString()).redirectErrorStream(true);
        builder.environment()
                .keySet()
                .removeIf(name -> INHERITED_PREFIXES_REMOVED.stream().anyMatch(name::startsWith));
        builder.environment().put("GATEWAY_PORT", "0");
        // The one setting the gateway refuses to start without (KeycloakRegistrationTest).
        builder.environment().put("GATEWAY_CLIENT_SECRET", "a-test-client-secret");
        return builder.start();
    }

    /**
     * Reads the gateway's output until it reports having started, keeping the last lines for a
     * failure message and recording the port Tomcat reports binding on the way.
     *
     * @return true once the started line appears; false if the output ends, which means the process
     *     exited first
     */
    private static boolean readUntilStarted(Process gateway, Deque<String> tail, AtomicInteger boundPort) {
        BufferedReader output =
                new BufferedReader(new InputStreamReader(gateway.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = output.readLine()) != null) {
                if (!STACK_FRAME.matcher(line).find()) {
                    synchronized (tail) {
                        tail.addLast(line);
                        if (tail.size() > TAIL_LINES) {
                            tail.removeFirst();
                        }
                    }
                }
                Matcher bound = BOUND_PORT_LINE.matcher(line);
                if (bound.find()) {
                    boundPort.set(Integer.parseInt(bound.group(1)));
                }
                if (line.contains(STARTED_LINE)) {
                    // Keep reading in the background, so a full pipe can never stall the gateway
                    // while the probe below is answered.
                    Thread.ofVirtual().start(() -> drain(output));
                    return true;
                }
            }
            output.close();
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads `output` to its end, which comes when `stop` ends the process. */
    private static void drain(BufferedReader output) {
        try (output) {
            while (output.readLine() != null) {
                // The lines themselves are not needed: reading them keeps the pipe from filling.
            }
        } catch (IOException e) {
            // The stream closes under this reader when stop() destroys the process. That is the
            // end this loop waits for, not a failure of the test.
        }
    }

    private static void stop(Process gateway) throws InterruptedException {
        gateway.destroy();
        if (!gateway.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            gateway.destroyForcibly();
        }
    }

    private static String joined(Deque<String> tail) {
        synchronized (tail) {
            return String.join(System.lineSeparator(), tail);
        }
    }
}
