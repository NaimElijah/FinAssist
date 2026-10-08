package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.naimelijah.finassist.ledger.TestTokens;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * A JWKS endpoint that answers {@code 200} with a body that is not a key set is an outage, never a
 * refused token — the ledger half of #121, which asked that the assistant be brought into line with
 * it.
 *
 * <p>Drives the real decoder {@link LedgerSecurityConfiguration#jwtDecoder} builds against a JDK
 * {@link HttpServer} serving each body. The contract it pins is the exception's type, because that
 * is what decides the status: {@code NimbusJwtDecoder} surfaces a key set it cannot parse as a plain
 * {@link JwtException}, never the {@link BadJwtException} every genuine rejection is, and {@code
 * JwtAuthenticationProvider} wraps only the former in {@code AuthenticationServiceException}, which
 * {@link ProblemAuthenticationEntryPoint} answers 503 {@code AUTHENTICATION_UNAVAILABLE} with the
 * outage's ERROR line. {@code LedgerKeySourceOutageIT} proves that last step over HTTP for an
 * unreachable source; this class proves an unreadable one raises the type that selects the same
 * branch.
 *
 * <p>The two JSON bodies, {@code []} and {@code {}}, are where the services differ until #126 is
 * closed: the assistant answers both 401, because PyJWT raises its generic key-client errors for
 * them — the same types it raises for a token naming an unknown key. They are pinned here so that
 * difference is recorded against the ledger's actual behaviour rather than a reading of its sources.
 */
class LedgerJwksBodyTest {

    private static final String ISSUER = "http://localhost:8081/realms/finassist";
    private static final String AUDIENCE = "finassist-ledger";
    private static final String CERTS_PATH = "/certs";

    /**
     * The one address the server binds and the decoder dials. Named rather than taken from {@code
     * InetAddress.getLoopbackAddress()}, which is {@code ::1} under {@code
     * java.net.preferIPv6Addresses=true}, so the two can never disagree.
     */
    private static final String LOOPBACK = "127.0.0.1";

    private static final String HTML = "text/html";
    private static final String JSON = "application/json";

    /** The decoder never reaches its validators here, but it is built with a fixed clock all the same. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    static Stream<Arguments> bodiesThatAreNotAKeySet() {
        return Stream.of(
                Arguments.of(
                        "not JSON",
                        HTML,
                        "<html><body>Down for maintenance</body></html>".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("not UTF-8", HTML, new byte[] {(byte) 0x80, (byte) 0x81}),
                Arguments.of(
                        "nested too deep to parse", HTML, "[".repeat(100_000).getBytes(StandardCharsets.UTF_8)),
                Arguments.of("a JSON array", JSON, "[]".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("a JSON object with no keys member", JSON, "{}".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Counts the requests the endpoint served, so a pass cannot come from a server that was never
     * reached: a refused connection is a plain {@link JwtException} too, and would satisfy the
     * type assertion on its own.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesThatAreNotAKeySet")
    void Given_a_jwks_endpoint_answering_a_body_that_is_not_a_key_set_When_a_token_is_decoded_Then_it_is_an_outage(
            String description, String contentType, byte[] body) throws IOException {
        AtomicInteger served = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.createContext(CERTS_PATH, exchange -> {
            served.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String jwksUri = "http://" + LOOPBACK + ":" + server.getAddress().getPort() + CERTS_PATH;
            JwtDecoder decoder = new LedgerSecurityConfiguration().jwtDecoder(jwksUri, ISSUER, AUDIENCE, FIXED_CLOCK);
            String token = TestTokens.owner();

            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(JwtException.class)
                    .isNotInstanceOf(BadJwtException.class);
            assertThat(served.get()).as("requests the JWKS endpoint served").isPositive();
        } finally {
            server.stop(0);
        }
    }
}
