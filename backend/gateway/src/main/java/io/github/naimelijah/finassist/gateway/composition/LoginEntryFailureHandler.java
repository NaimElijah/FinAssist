package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Answers a request to start a sign-in that cannot be started, in the gateway's own problem shape
 * (ADR-030).
 *
 * <p><b>The one case that reaches it.</b> {@link BffRoutes#LOGIN_ENTRY_PATTERN} admits any single
 * segment after {@code /bff/oauth2/authorization/}, and only {@code keycloak} resolves to a
 * registration. For every other segment {@code DefaultOAuth2AuthorizationRequestResolver} throws
 * {@code InvalidClientRegistrationIdException}, which {@link
 * OAuth2AuthorizationRequestRedirectFilter} hands to the failure handler set on it — and left at
 * Spring's default, that handler ends in {@code sendError(500)} and logs the whole exception at
 * WARN, so Boot's error page answered an unauthenticated caller and the caller's own path segment
 * reached the log with a stack trace behind it. Anyone who can reach this gateway could fill its
 * log by asking for providers that do not exist.
 *
 * <p><b>Why {@code 401 UNAUTHENTICATED} and not something of its own.</b> That is what the chain's
 * own rules already say the answer is: a path the gateway does not publish is refused by {@code
 * anyRequest().denyAll()}, and an anonymous caller is sent to {@link
 * ProblemAuthenticationEntryPoint}, which is exactly how {@code /login}, {@code /error} and any
 * invented path are answered. This filter runs ahead of {@code AuthorizationFilter} and so never
 * lets that rule be reached; delegating to the same entry point puts the answer back. It also
 * declares nothing new — the gateway's vocabulary stays the set {@link GatewayProblems} lists —
 * and tells a stranger nothing about which registration ids exist.
 *
 * <p><b>Nothing is logged for it</b>, for {@link ProblemAuthenticationEntryPoint}'s own reason: the
 * answer is the ordinary refusal any unrecognised path gets, there is nothing diagnostic in it, and
 * a line per request is the log-filling this class exists to stop.
 *
 * <p><b>Anything else is a gateway failure and is loud.</b> Only an unknown registration id is
 * answered as a refusal; every other cause is answered {@code 500 INTERNAL_ERROR} and logged at
 * ERROR by the cause's class name alone — never its message, never a stack trace — matching {@link
 * GatewayRelayProblems}'s catch-all, which cannot enumerate what an arbitrary exception's text
 * might quote.
 */
final class LoginEntryFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LoginEntryFailureHandler.class);

    /**
     * The cause that means "no such registration", named as text because Spring Security 7.1.2
     * declares {@code InvalidClientRegistrationIdException} package-private — it cannot be imported
     * and {@code instanceof} cannot see it. Matching a class name is brittle by nature, so it is
     * made self-reporting instead of trusted: were Spring to rename or move it, this handler would
     * answer {@code 500} rather than the refusal, and {@code GatewaySecurityTest}'s chain-level
     * case, which drives a real unknown segment through the real filter, would turn red.
     */
    private static final String UNKNOWN_REGISTRATION_ID_FAILURE =
            "org.springframework.security.oauth2.client.web.InvalidClientRegistrationIdException";

    private static final String UNEXPECTED_DETAIL = "The gateway could not start a sign-in.";

    private static final byte[] UNEXPECTED_BODY =
            GatewayProblems.write(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_DETAIL, GatewayProblems.INTERNAL_ERROR);

    /** Stands in when a failure carries no cause, so the ERROR line still names something. */
    private static final String NO_CAUSE = "none";

    private final ProblemAuthenticationEntryPoint refuse = new ProblemAuthenticationEntryPoint();

    /**
     * @param request the request that asked to start a sign-in
     * @param response the response to write, which no filter later in the chain may then write to
     * @param failure the redirect filter's wrapper; its cause is what actually went wrong
     * @throws IOException if the problem body cannot be written
     */
    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        Throwable cause = failure.getCause();
        if (cause != null
                && UNKNOWN_REGISTRATION_ID_FAILURE.equals(cause.getClass().getName())) {
            refuse.commence(request, response, failure);
            return;
        }
        LOG.error(
                "A sign-in could not be started: {}",
                cause == null ? NO_CAUSE : cause.getClass().getName());
        GatewayProblems.send(response, HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_BODY);
    }
}
