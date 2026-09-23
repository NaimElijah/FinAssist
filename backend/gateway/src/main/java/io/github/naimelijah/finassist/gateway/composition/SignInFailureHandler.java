package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
        return (exception instanceof OAuth2AuthenticationException oauth2)
                ? oauth2.getError().getErrorCode()
                : UNKNOWN_ERROR_CODE;
    }
}
