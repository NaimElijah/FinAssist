package io.github.naimelijah.finassist.ledger.composition;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.Key;
import java.util.List;

/**
 * Turns a key set that holds no usable key into an outage, rather than a refused token (ADR-037).
 *
 * <p>Spring Security parses {@code {"keys": []}}, or a set whose every key is of a type Nimbus
 * skips, without complaint, then finds no key for the token and raises a {@code BadJwtException}:
 * 401, logged as one caller's {@code unknown_key}. But no token at all can be verified against such
 * a set, so it is the same failure as an unreachable source, and has to page someone.
 *
 * <p>Wraps the {@link JWSVerificationKeySelector} Spring builds and changes one case only. When that
 * selector finds no key for a token, this asks the same {@link JWKSource} for every key it holds;
 * if there are none, it raises {@link KeySourceException}. {@code NimbusJwtDecoder} surfaces that as
 * a plain {@code JwtException}, which {@link ProblemAuthenticationEntryPoint} answers 503 {@code
 * AUTHENTICATION_UNAVAILABLE}. A set that holds keys, only not this token's, is left a refusal. The
 * extra lookup goes through the same cached source, and only once a token's key was already missing.
 *
 * <p>Pinned by {@code LedgerJwksBodyTest}, which serves each body to the real decoder.
 */
final class EmptyKeySetIsAnOutage implements JWSKeySelector<SecurityContext> {

    private static final JWKSelector EVERY_KEY = new JWKSelector(new JWKMatcher.Builder().build());

    private final JWSVerificationKeySelector<SecurityContext> delegate;

    private EmptyKeySetIsAnOutage(JWSVerificationKeySelector<SecurityContext> delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps the key selector {@code NimbusJwtDecoder}'s builder installed.
     *
     * @param selector the selector to wrap; must be the {@link JWSVerificationKeySelector} Spring
     *     builds for a JWKS URI, since that is the one whose key source this can ask
     * @return a selector answering exactly as {@code selector} does, except for an empty key set
     * @throws IllegalStateException if {@code selector} is any other type. That fails the ledger at
     *     start-up rather than quietly giving up the empty-set check after a Spring upgrade
     */
    static EmptyKeySetIsAnOutage around(JWSKeySelector<SecurityContext> selector) {
        if (!(selector instanceof JWSVerificationKeySelector<SecurityContext> verification)) {
            throw new IllegalStateException(
                    "Expected the JWSVerificationKeySelector Spring builds for a JWKS URI, but got "
                            + (selector == null ? "none" : selector.getClass().getName()));
        }
        return new EmptyKeySetIsAnOutage(verification);
    }

    /**
     * The keys that may have signed a token with this {@code header}.
     *
     * @throws KeySourceException if the key set could not be fetched or read, as before, or if it
     *     holds no key at all
     */
    @Override
    public List<? extends Key> selectJWSKeys(JWSHeader header, SecurityContext context) throws KeySourceException {
        List<Key> keys = delegate.selectJWSKeys(header, context);
        if (keys.isEmpty() && delegate.getJWKSource().get(EVERY_KEY, context).isEmpty()) {
            throw new KeySourceException("The JWK set holds no key a token could be verified against");
        }
        return keys;
    }
}
