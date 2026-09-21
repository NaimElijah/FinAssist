package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers every request that needs a session and has none with {@code 401} and an RFC 9457
 * problem — never a redirect to Keycloak (ADR-030).
 *
 * <p><b>This is the reason the class exists.</b> {@code oauth2Login} installs a
 * {@code LoginUrlAuthenticationEntryPoint} of its own, which answers {@code 302} to the provider.
 * A {@code fetch} following that redirect would receive Keycloak's HTML sign-in page, or a CORS
 * failure, in place of a status it could act on, and the web client would show a mystery rather
 * than "sign in". Registering this one through {@code exceptionHandling} overrides that default:
 * Spring's {@code ExceptionHandlingConfigurer} prefers an explicitly set entry point over every
 * default a login configurer registered. A sign-in therefore begins only when the browser
 * navigates to {@link BffRoutes#LOGIN_ENTRY} itself.
 *
 * <p>No {@code WWW-Authenticate} header: the gateway takes no credential on a request, only a
 * session cookie. The ledger and the assistant, which do read a bearer token, send the challenge
 * (ADR-030).
 *
 * <p>The body is fixed and says nothing about the request — not the path, not a header, not a
 * cookie. Nothing here is logged either: an unauthenticated request is the ordinary state of a
 * browser that has not signed in yet, and logging one per page load would be noise that also
 * risked carrying a request detail into the log.
 */
final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String DETAIL = "This request needs a signed-in session. Sign in at " + BffRoutes.LOGIN_ENTRY
            + " and send the session cookie with the request.";

    /** Serialised once: nothing in it varies by request, which is the point. */
    private static final byte[] BODY =
            GatewayProblems.write(HttpStatus.UNAUTHORIZED, DETAIL, GatewayProblems.UNAUTHENTICATED);

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        GatewayProblems.send(response, HttpStatus.UNAUTHORIZED, BODY);
    }
}
