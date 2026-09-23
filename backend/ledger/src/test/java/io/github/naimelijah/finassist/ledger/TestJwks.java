package io.github.naimelijah.finassist.ledger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A real JWKS endpoint, backed by one RSA key generated once per JVM, so {@code LedgerAuthorizationIT}
 * and its siblings prove the whole validation chain against a genuine {@link
 * com.nimbusds.jose.crypto.RSASSAVerifier} rather than a mocked {@code JwtDecoder}.
 *
 * <p>A JDK {@link HttpServer} on {@code 127.0.0.1:0} rather than a mocking library: the key set has to
 * be fetched over real HTTP for {@code NimbusJwtDecoder.withJwkSetUri} to exercise the same code path
 * production does, and the JDK already ships everything a static JSON response needs.
 *
 * <p>One instance for the whole JVM, mirroring {@link PostgresFixture}'s container: generating an RSA
 * key pair is not free, and every test that wants a validly signed token wants the same one.
 */
final class TestJwks {

    /** Where the key set is served, matching {@code NimbusJwtDecoder.withJwkSetUri}'s expectation. */
    private static final String CERTS_PATH = "/certs";

    private static final int RSA_KEY_SIZE_BITS = 2048;

    private static final TestJwks INSTANCE = new TestJwks();

    private final RSAKey key;
    private final String jwksUri;

    private TestJwks() {
        this.key = generate("test-signing-key");
        this.jwksUri = start(this.key);
    }

    static TestJwks instance() {
        return INSTANCE;
    }

    /** The URI {@code finassist.security.jwks-uri} is overridden to for every test that extends {@link PostgresFixture}. */
    String jwksUri() {
        return jwksUri;
    }

    /** The key {@link TestTokens} signs every real token with, so it verifies against this set. */
    RSAKey signingKey() {
        return key;
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(RSA_KEY_SIZE_BITS)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the test JWKS signing key", e);
        }
    }

    private static String start(RSAKey key) {
        byte[] body = ("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}").getBytes(StandardCharsets.UTF_8);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(CERTS_PATH, exchange -> respond(exchange, body));
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort() + CERTS_PATH;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the test JWKS server", e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
