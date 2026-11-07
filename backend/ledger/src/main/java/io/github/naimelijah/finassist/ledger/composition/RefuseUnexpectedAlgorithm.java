package io.github.naimelijah.finassist.ledger.composition;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Refuses a token whose header cannot be read, or names any algorithm but RS256, as {@code
 * malformed}, before a key is looked up (ADR-076).
 *
 * <p>The decoder it wraps refuses both on its own, since it accepts RS256 alone, and stays the
 * defence. This check exists for the category, decided from the header the way the assistant's
 * {@code _require_algorithm} decides it. Left to Nimbus, an HS256 token is refused with "Another
 * algorithm expected, or no matching key(s) found", which {@link ProblemAuthenticationEntryPoint}
 * could only read as {@code unknown_key}, though the algorithm says nothing about which keys exist.
 * Refusing first also spares the key source a fetch for a token no key could ever verify.
 *
 * <p>The refusal is a {@link JwtValidationException} carrying the category as its error's
 * description, the typed channel the token validators already use, so the entry point reads it
 * without parsing a message.
 */
final class RefuseUnexpectedAlgorithm implements JwtDecoder {

    private static final String REFUSAL = "The token's header cannot be read, or names an algorithm other than RS256";

    private final JwtDecoder delegate;

    private RefuseUnexpectedAlgorithm(JwtDecoder delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps the decoder that verifies a token once its header names RS256.
     *
     * @param delegate the decoder to call for a token whose header names RS256
     * @return a decoder refusing every other token as {@code malformed}
     */
    static RefuseUnexpectedAlgorithm around(JwtDecoder delegate) {
        return new RefuseUnexpectedAlgorithm(delegate);
    }

    /**
     * Decodes {@code token} through the wrapped decoder, once its header names RS256.
     *
     * @throws JwtValidationException if the header cannot be read or names another algorithm, its one
     *     error describing the category {@code malformed}
     * @throws JwtException whatever the wrapped decoder throws for a token it refuses
     */
    @Override
    public Jwt decode(String token) throws JwtException {
        Algorithm algorithm;
        try {
            algorithm = JWTParser.parse(token).getHeader().getAlgorithm();
        } catch (ParseException | RuntimeException unreadable) {
            // Nimbus does not report every unreadable header as a ParseException: a header that is
            // JSON null throws a NullPointerException, a negative PBES2 count an
            // IllegalArgumentException. NimbusJwtDecoder.parse catches Exception at this same call
            // for that reason, and anything less would escape the security chain as a 500.
            // Not attached as the cause: Nimbus's parse messages can quote the header they could
            // not read, and nothing about a refusal may carry the token (ADR-030).
            throw malformed();
        }
        if (!JWSAlgorithm.RS256.equals(algorithm)) {
            throw malformed();
        }
        return delegate.decode(token);
    }

    private static JwtValidationException malformed() {
        return new JwtValidationException(
                REFUSAL,
                List.of(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN, ProblemAuthenticationEntryPoint.MALFORMED, null)));
    }
}
