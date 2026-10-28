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
 * per scenario ADR-030's reason categories exist to describe.
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

    /** Matches the ledger's fixed audience (ADR-030; not a setting). */
    static final String AUDIENCE = "finassist-ledger";

    private static final String WRONG_ISSUER = "http://attacker.example/realms/finassist";
    private static final String WRONG_AUDIENCE = "finassist-assistant-impersonation";

    /**
     * The instant every token is issued relative to, and the instant {@link PostgresFixture} fixes
     * the ledger's {@code Clock} bean at, so a token's validity never depends on when a test runs: a
     * fork reaching an {@code *IT} more than ten minutes after this class loaded would otherwise find
     * the "future" tokens valid, and one an hour later would refuse every owner request. Deliberately
     * far from any real date, so a validator that read the wall clock instead would fail every test.
     */
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

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

    /**
     * Otherwise valid, but issued by the machine's real clock rather than {@link #NOW}: for a ledger
     * reading the fixed clock it is issued in the future and must be refused. What proves the ledger
     * validates against the clock it is given, not the one on the wall.
     */
    public static String issuedByTheWallClock() {
        Instant wallClock = Instant.now();
        return signed(claims("owner-user", List.of("owner"))
                .issueTime(Date.from(wallClock))
                .expirationTime(Date.from(wallClock.plus(LIFETIME)))
                .build());
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

    /**
     * {@code iat} beyond the 30 s skew in the future: a token that claims to have been issued
     * after now, which {@code LedgerSecurityConfiguration}'s {@code iat} validator refuses on its
     * own merits, without touching {@code exp}, {@code nbf}, or any other claim.
     */
    public static String futureIssuedAt() {
        return signed(claims("owner-user", List.of("owner"))
                .issueTime(Date.from(NOW.plus(BEYOND_SKEW)))
                .build());
    }

    /**
     * No {@code iat} claim at all — a real signature, a real issuer and audience, nothing to check
     * the token's own age against. ADR-030 requires {@code iat}, so its absence alone
     * must refuse this token.
     */
    public static String noIssuedAt() {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("owner-user")
                .audience(AUDIENCE)
                .expirationTime(Date.from(NOW.plus(LIFETIME)))
                .claim("roles", List.of("owner"));
        return signed(claims.build());
    }

    /**
     * No {@code exp} claim at all — a real signature, a real issuer, audience and {@code iat}, and
     * no moment at which it stops being valid. ADR-030 requires {@code exp}, so its absence alone
     * must refuse this token, exactly as the assistant already refuses the same shape.
     */
    public static String noExpiry() {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("owner-user")
                .audience(AUDIENCE)
                .issueTime(Date.from(NOW))
                .claim("roles", List.of("owner"));
        return signed(claims.build());
    }

    /**
     * A genuine {@link #viewer()} token whose payload segment is swapped for one naming {@code
     * owner}, keeping the original header and signature segments untouched — the textbook JWT
     * integrity attack, and exactly how a viewer would try to become an owner: take a real token,
     * edit the claims, keep the signature. Both payloads are signed by the same real key with an
     * identical header, so only the payload actually differs between the two tokens combined here
     * — which is why the kept signature no longer verifies against it.
     */
    public static String tamperedSignature() {
        String[] genuine = viewer().split("\\.", 3);
        String[] tampered =
                signed(claims("viewer-user", List.of("owner")).build()).split("\\.", 3);
        return genuine[0] + "." + tampered[1] + "." + genuine[2];
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
