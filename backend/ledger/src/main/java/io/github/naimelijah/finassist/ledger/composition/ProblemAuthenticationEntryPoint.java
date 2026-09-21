package io.github.naimelijah.finassist.ledger.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers every request that reaches the ledger with no valid identity: {@code 401} {@code
 * UNAUTHENTICATED} for a missing or rejected token, or {@code 503} {@code
 * AUTHENTICATION_UNAVAILABLE} when the JWKS source itself could not be reached (ADR-030).
 *
 * <p>The 503 branch is why this class exists rather than the default {@code
 * BearerTokenAuthenticationEntryPoint}: a JWKS outage must never look like a bad token, because
 * nothing here says whether that token would have been valid. {@code
 * NimbusJwtDecoder} surfaces a key-source failure as a plain {@link JwtException} — not a {@link
 * org.springframework.security.oauth2.jwt.BadJwtException}, the type every genuine token rejection
 * is — which {@code JwtAuthenticationProvider} wraps in {@link AuthenticationServiceException} rather
 * than {@code InvalidBearerTokenException}. That distinction, made in the framework, is what this
 * class branches on.
 *
 * <p>{@code WWW-Authenticate} tells the caller which of the two 401 cases it hit, per RFC 6750: a
 * bare {@code Bearer} when the request carried no {@code Authorization} header at all, or {@code
 * Bearer error="invalid_token"} when it did and the token was refused. Reading the request's own
 * header for that choice, rather than the exception type, is what keeps it correct regardless of
 * which filter raised the exception.
 *
 * <p>Every refusal is logged once at WARN, carrying only the reason category from ADR-030's
 * Vocabulary — never the token, never a claim. The category comes from the decoder's own exception:
 * a {@link JwtValidationException} carries the {@link OAuth2Error} {@code
 * LedgerSecurityConfiguration}'s validators built, whose description already <em>is</em> the
 * category; anything else is read from Nimbus's own (English-language, not machine-typed) failure
 * message, which is the only signal it gives for a malformed token, an algorithm it refused, or a
 * key it could not find.
 */
final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemAuthenticationEntryPoint.class);

    /** The key a structured log line carries the reason under; never the token itself. */
    private static final String REASON_KEY = "reason";

    // The reason categories ADR-030's Vocabulary fixes. Package-visible because
    // LedgerSecurityConfiguration builds its validators' OAuth2Error descriptions from these same
    // constants, so a description this class reads back is always one of the words below and never
    // a validator's own free-text message.
    static final String MISSING = "missing";
    static final String MALFORMED = "malformed";
    static final String REJECTED = "rejected";
    static final String LIFETIME = "lifetime";
    static final String ISSUER = "issuer";
    static final String AUDIENCE = "audience";
    static final String SUBJECT = "subject";
    static final String UNKNOWN_KEY = "unknown_key";
    static final String KEY_SOURCE = "key_source";

    private static final String BEARER_CHALLENGE = "Bearer";
    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    private static final String UNAUTHENTICATED_DETAIL = "This request needs a valid bearer token.";
    private static final String UNAVAILABLE_DETAIL =
            "The authentication service could not be reached; this token's validity could not be decided.";

    private static final byte[] UNAUTHENTICATED_BODY =
            problem(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL, Problems.UNAUTHENTICATED);

    private static final byte[] UNAVAILABLE_BODY =
            problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_DETAIL, Problems.AUTHENTICATION_UNAVAILABLE);

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (authException instanceof AuthenticationServiceException) {
            log(KEY_SOURCE);
            send(response, HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_BODY);
            return;
        }
        log(reasonFor(authException));
        boolean tokenWasSent = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, tokenWasSent ? INVALID_TOKEN_CHALLENGE : BEARER_CHALLENGE);
        send(response, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_BODY);
    }

    /**
     * A {@link JwtValidationException}'s errors already carry the category, because {@code
     * LedgerSecurityConfiguration}'s validators build them that way; every other rejection is
     * categorised from Nimbus's own message, since it gives nothing more structured than English.
     */
    private static String reasonFor(AuthenticationException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof JwtValidationException validation) {
            return validation.getErrors().stream()
                    .findFirst()
                    .map(OAuth2Error::getDescription)
                    .orElse(REJECTED);
        }
        if (!(cause instanceof JwtException jwtException)) {
            return MISSING;
        }
        String message = String.valueOf(jwtException.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("malformed") || message.contains("unsupported algorithm")) {
            return MALFORMED;
        }
        if (message.contains("no matching key")) {
            return UNKNOWN_KEY;
        }
        return REJECTED;
    }

    private static void log(String reason) {
        LOG.atWarn().addKeyValue(REASON_KEY, reason).log("Refused a request with no valid bearer token: {}", reason);
    }

    private static void send(HttpServletResponse response, HttpStatus status, byte[] body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** Serialised once per status: nothing in either body varies by request. */
    private static byte[] problem(HttpStatus status, String detail, String code) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(Problems.BLANK_TYPE);
        problemDetail.setProperty(Problems.CODE_MEMBER, code);
        return JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build()
                .writeValueAsBytes(problemDetail);
    }
}
