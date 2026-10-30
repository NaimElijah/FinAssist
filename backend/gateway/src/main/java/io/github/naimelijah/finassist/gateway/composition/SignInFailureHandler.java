package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Sends a failed sign-in back to the web client at {@code GATEWAY_PUBLIC_URL/?signin=failed}, and
 * logs the OAuth2 error code and nothing else (ADR-030).
 *
 * <p>Written rather than configured because of that second half. Spring's {@code
 * SimpleUrlAuthenticationFailureHandler} logs the {@link AuthenticationException} itself and saves
 * it in the session, and an {@link OAuth2AuthenticationException} raised during the code exchange
 * carries the authorization server's description — which can quote the request, and in the worst
 * case an authorization code. Only {@code OAuth2Error#getErrorCode()} is logged here: a short,
 * fixed vocabulary such as {@code invalid_grant} or {@code access_denied} that says what went
 * wrong without saying what was sent.
 *
 * <p>The vocabulary is fixed here rather than trusted. A provider's error response reaches the
 * callback as its {@code error} query parameter, and Spring carries that parameter into the error
 * code as it arrived, so whoever sent the callback chose it. A code outside {@link
 * #REGISTERED_ERROR_CODES} is logged as {@code unrecognised}, never as sent.
 *
 * <p>The redirect target is absolute and built from {@code GATEWAY_PUBLIC_URL}, never from the
 * request: the callback arrives through the web client's dev proxy, so the request's own host is
 * whatever that proxy chose to pass on (ADR-030).
 */
final class SignInFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SignInFailureHandler.class);

    /** What the web client looks for to show "sign-in failed" on the page it lands back on. */
    static final String FAILURE_QUERY = "?signin=failed";

    /** Stands in when the failure is not an OAuth2 one, so a line is still emitted and still says nothing. */
    private static final String UNKNOWN_ERROR_CODE = "unknown";

    /** Stands in for an error code outside {@link #REGISTERED_ERROR_CODES}, whatever it said. */
    private static final String UNRECOGNISED_ERROR_CODE = "unrecognised";

    /**
     * Every error code a failed sign-in can legitimately carry, and so may be logged as itself: the
     * authorization endpoint's (RFC 6749 §4.1.2.1), the token endpoint's (§5.2), OpenID Connect's
     * authentication errors (Core §3.1.2.6), and the codes Spring Security 7.1.2 raises itself while
     * completing a sign-in, read from its {@code oauth2-client} sources.
     */
    private static final Set<String> REGISTERED_ERROR_CODES = Set.of(
            // RFC 6749 §4.1.2.1 and §5.2
            "invalid_request",
            "unauthorized_client",
            "access_denied",
            "unsupported_response_type",
            "invalid_scope",
            "server_error",
            "temporarily_unavailable",
            "invalid_client",
            "invalid_grant",
            "unsupported_grant_type",
            // OpenID Connect Core 1.0 §3.1.2.6
            "interaction_required",
            "login_required",
            "account_selection_required",
            "consent_required",
            "invalid_request_uri",
            "invalid_request_object",
            "request_not_supported",
            "request_uri_not_supported",
            "registration_not_supported",
            // Spring Security's own, raised at the callback
            "authorization_request_not_found",
            "client_registration_not_found",
            "invalid_state_parameter",
            "invalid_token_response",
            "invalid_id_token",
            "invalid_nonce",
            "missing_signature_verifier",
            "invalid_user_info_response",
            "missing_user_info_uri",
            "missing_user_name_attribute");

    private final RedirectStrategy redirects = new DefaultRedirectStrategy();
    private final String failureUrl;

    /**
     * @param webOrigin the origin the browser reaches the web client through
     */
    SignInFailureHandler(Origin webOrigin) {
        this.failureUrl = webOrigin.root() + FAILURE_QUERY;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        LOG.warn("Sign-in failed with OAuth2 error code {}", errorCodeOf(exception));
        redirects.sendRedirect(request, response, failureUrl);
    }

    private static String errorCodeOf(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth2)) {
            return UNKNOWN_ERROR_CODE;
        }
        String code = oauth2.getError().getErrorCode();
        return REGISTERED_ERROR_CODES.contains(code) ? code : UNRECOGNISED_ERROR_CODE;
    }
}
