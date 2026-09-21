package io.github.naimelijah.finassist.ledger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Real, signed bearer tokens for {@code LedgerAuthorizationIT} and its siblings, one factory method
 * per scenario the Vocabulary's reason categories exist to describe.
 *
 * <p>Every token is signed against {@link TestJwks#instance()}'s key, except {@link #foreignKey()},
 * which is signed by a key never published there, and {@link #hmacConfused()}, which uses the wrong
 * algorithm family entirely. Real signing rather than a mocked {@code Jwt} is the point: it proves
 * {@code LedgerSecurityConfiguration}'s decoder rejects a bad token on its own merits, not because a
 * test told it to.
 *
 * <p>{@code typ: JWT} is set on every header, matching what Keycloak actually issues; a decoder that
 * validated it would otherwise never be exercised by any of these tokens.
 *
 * <p>Public because the six {@code RANDOM_PORT} integration tests that send the owner token live in
 * each context's own {@code adapter.in.web} package, not this one.
 */
public final class TestTokens {

    /** Matches {@code finassist.security.issuer}'s shipped default, so a valid token needs no override. */
    static final String ISSUER = "http://localhost:8081/realms/finassist";

    /** Matches the ledger's fixed audience (ADR-030's Vocabulary; not a setting). */
    static final String AUDIENCE = "finassist-ledger";

    private static final String WRONG_ISSUER = "http://attacker.example/realms/finassist";
    private static final String WRONG_AUDIENCE = "finassist-assistant-impersonation";

    private static final Instant NOW = Instant.now();
    private static final Duration LIFETIME = Duration.ofHours(1);

    /** Well beyond {@code JwtTimestampValidator}'s 30 s skew in either direction. */
    private static final Duration BEYOND_SKEW = Duration.ofMinutes(10);

    /** A second key, generated once, never served by {@link TestJwks}: what {@link #foreignKey()} signs with. */
    private static final RSAKey FOREIGN_KEY = generateForeignKey();

    /**
     * Exactly 32 bytes (256 bits): {@code MACSigner} rejects anything shorter for HS256. The value
     * itself is never checked against anything real.
     */
    private static final byte[] HMAC_SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private TestTokens() {}

    public static String owner() {
        return signed(claims("owner-user", List.of("owner")).build());
    }

    public static String viewer() {
        return signed(claims("viewer-user", List.of("viewer")).build());
    }

    public static String assistant() {
        return signed(claims("assistant-service", List.of("assistant")).build());
    }

    /** A real, otherwise valid token whose {@code roles} claim grants nothing: authenticated, authorised for nothing. */
    public static String noRoles() {
        return signed(claims("no-roles-user", List.of()).build());
    }

    /**
     * {@code iat} moves back too, not only {@code exp}: {@code Jwt}'s own constructor requires
     * {@code expiresAt} to be after {@code issuedAt}, and the baseline {@code claims()} sets {@code
     * iat} to {@link #NOW}, which would otherwise sit after an {@code exp} moved into the past.
     */
    public static String expired() {
        return signed(claims("owner-user", List.of("owner"))
                .issueTime(Date.from(NOW.minus(BEYOND_SKEW.multipliedBy(2))))
                .expirationTime(Date.from(NOW.minus(BEYOND_SKEW)))
                .build());
    }

    public static String notYetValid() {
        return signed(claims("owner-user", List.of("owner"))
                .notBeforeTime(Date.from(NOW.plus(BEYOND_SKEW)))
                .build());
    }

    public static String wrongAudience() {
        return signed(claims("owner-user", List.of("owner"))
                .audience(List.of(WRONG_AUDIENCE))
                .build());
    }

    public static String wrongIssuer() {
        return signed(
                claims("owner-user", List.of("owner")).issuer(WRONG_ISSUER).build());
    }

    public static String noSubject() {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(LIFETIME)))
                .claim("roles", List.of("owner"));
        return signed(claims.build());
    }

    /** {@code alg: none}. Rejected before a signature is even checked. */
    public static String unsigned() {
        PlainHeader header = new PlainHeader.Builder().type(JOSEObjectType.JWT).build();
        return new PlainJWT(header, claims("owner-user", List.of("owner")).build()).serialize();
    }

    /** HS256 with an arbitrary secret, sent to a decoder that accepts RS256 only: an algorithm the key set never allowed. */
    public static String hmacConfused() {
        JWTClaimsSet claimsSet = claims("owner-user", List.of("owner")).build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        try {
            jwt.sign(new MACSigner(HMAC_SECRET));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the HMAC-confusion test token", e);
        }
        return jwt.serialize();
    }

    /** RS256, correctly signed, by a key this ledger's JWKS never publishes: no candidate key exists for it. */
    public static String foreignKey() {
        JWTClaimsSet claimsSet = claims("owner-user", List.of("owner")).build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(FOREIGN_KEY.getKeyID())
                .build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        try {
            jwt.sign(new RSASSASigner(FOREIGN_KEY));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the foreign-key test token", e);
        }
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder claims(String subject, List<String> roles) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(LIFETIME)))
                .claim("roles", roles);
    }

    private static String signed(JWTClaimsSet claimsSet) {
        RSAKey key = TestJwks.instance().signingKey();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.getKeyID())
                .build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign a test token", e);
        }
        return jwt.serialize();
    }

    private static RSAKey generateForeignKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID("foreign-key")
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the foreign-key test token's key", e);
        }
    }
}
