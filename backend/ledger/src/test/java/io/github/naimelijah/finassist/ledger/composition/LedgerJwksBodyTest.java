package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * A JWKS endpoint that answers {@code 200} with a body no token can be verified against is an
 * outage, never a refused token (ADR-037). That covers a body that is not a key set at all, the
 * ledger half of #121, and a key set holding no key the ledger can use, #126.
 *
 * <p>Drives the real decoder {@link LedgerSecurityConfiguration#jwtDecoder} builds against a JDK
 * {@link HttpServer} serving each body. The contract it pins is the exception's type, because that
 * is what decides the status: {@code NimbusJwtDecoder} surfaces a key set it cannot parse as a plain
 * {@link JwtException}, never the {@link BadJwtException} every genuine rejection is, and {@code
 * JwtAuthenticationProvider} wraps only the former in {@code AuthenticationServiceException}, which
 * {@link ProblemAuthenticationEntryPoint} answers 503 {@code AUTHENTICATION_UNAVAILABLE} with the
 * outage's ERROR line. {@code LedgerKeySourceOutageIT} proves that last step over HTTP for an
 * unreachable source; this class proves an unusable one raises the type that selects the same
 * branch.
 *
 * <p>Spring parses an empty key set, or one whose every key is of a type Nimbus skips, without
 * complaint, and then reports the token's key missing: a {@link BadJwtException}, answered 401.
 * {@link EmptyKeySetIsAnOutage} is what turns that into an outage, and only when the set is empty —
 * a set that holds other keys still refuses the token, which the last case here pins. The
 * assistant's {@code tests/test_auth.py} serves the same bodies, so the two services are held to one
 * answer.
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

    private static final int RSA_KEY_SIZE_BITS = 2048;

    private static final String HTML = "text/html";
    private static final String JSON = "application/json";

    /**
     * Valid JSON, and past the 4,300 digits CPython converts by default, so the assistant's {@code
     * json.load} refuses it (#126). The ledger has no such limit; the case is here so both services
     * are pinned against the same body.
     */
    private static final String LONG_INTEGER = "1".repeat(5_000);

    /** The decoder never reaches its validators here, but it is built with a fixed clock all the same. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    static Stream<Arguments> bodiesNoTokenCanBeVerifiedAgainst() {
        return Stream.of(
                Arguments.of(
                        "not JSON",
                        HTML,
                        "<html><body>Down for maintenance</body></html>".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("not UTF-8", HTML, new byte[] {(byte) 0x80, (byte) 0x81}),
                Arguments.of(
                        "nested too deep to parse", HTML, "[".repeat(100_000).getBytes(StandardCharsets.UTF_8)),
                Arguments.of("a JSON array", JSON, "[]".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("a JSON object with no keys member", JSON, "{}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "a JSON integer too long for Python to convert",
                        JSON,
                        LONG_INTEGER.getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "a key whose modulus is that integer",
                        JSON,
                        ("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-signing-key\",\"e\":\"AQAB\",\"n\":" + LONG_INTEGER
                                        + "}]}")
                                .getBytes(StandardCharsets.UTF_8)),
                Arguments.of("a key set with no keys", JSON, "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "a key set whose only key is of a type Nimbus skips",
                        JSON,
                        "{\"keys\":[{\"kty\":\"unsupported\",\"kid\":\"test-signing-key\"}]}"
                                .getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "a key member of the wrong JSON type",
                        JSON,
                        "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-signing-key\",\"alg\":[\"RS256\"]}]}"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesNoTokenCanBeVerifiedAgainst")
    void
            Given_a_jwks_endpoint_answering_a_body_no_token_can_be_verified_against_When_a_token_is_decoded_Then_it_is_an_outage(
                    String description, String contentType, byte[] body) throws IOException {
        AtomicInteger served = new AtomicInteger();
        HttpServer server = serve(contentType, body, served);
        try {
            JwtDecoder decoder = decoderFor(server);
            String token = TestTokens.owner();

            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(JwtException.class)
                    .isNotInstanceOf(BadJwtException.class);
            assertThat(served.get()).as("requests the JWKS endpoint served").isPositive();
        } finally {
            server.stop(0);
        }
    }

    /**
     * The line ADR-037 draws: a set that holds keys, only not the one this token names, is this
     * caller's problem and stays a refusal. Without this case, an {@link EmptyKeySetIsAnOutage} that
     * reported every missing key as an outage would still pass the test above.
     */
    @Test
    void Given_a_key_set_without_the_tokens_key_When_a_token_is_decoded_Then_it_is_refused() throws Exception {
        String anotherKey = new RSAKeyGenerator(RSA_KEY_SIZE_BITS)
                .keyID("another-key")
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate()
                .toPublicJWK()
                .toJSONString();
        AtomicInteger served = new AtomicInteger();
        HttpServer server = serve(JSON, ("{\"keys\":[" + anotherKey + "]}").getBytes(StandardCharsets.UTF_8), served);
        try {
            JwtDecoder decoder = decoderFor(server);
            String token = TestTokens.owner();

            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
            assertThat(served.get()).as("requests the JWKS endpoint served").isPositive();
        } finally {
            server.stop(0);
        }
    }

    /**
     * Serves {@code body} at {@link #CERTS_PATH}, counting each request in {@code served}, so a pass
     * cannot come from a server that was never reached: a refused connection is a plain {@link
     * JwtException} too, and would satisfy the outage assertion on its own.
     */
    private static HttpServer serve(String contentType, byte[] body, AtomicInteger served) throws IOException {
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
        return server;
    }

    private static JwtDecoder decoderFor(HttpServer server) {
        String jwksUri = "http://" + LOOPBACK + ":" + server.getAddress().getPort() + CERTS_PATH;
        return new LedgerSecurityConfiguration().jwtDecoder(jwksUri, ISSUER, AUDIENCE, FIXED_CLOCK);
    }
}
