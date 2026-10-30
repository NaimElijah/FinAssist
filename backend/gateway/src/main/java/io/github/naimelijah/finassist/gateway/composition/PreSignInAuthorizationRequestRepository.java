package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Keeps a started sign-in's authorization request in the session, exactly as Spring's {@link
 * HttpSessionOAuth2AuthorizationRequestRepository} does, and gives a session it creates for that
 * purpose a short idle limit (ADR-030).
 *
 * <p>The login entry is public, and every request to it that arrives without a session makes one
 * to hold the request. Left at the configured half hour, a sign-in started and never finished would
 * hold its memory for thirty minutes, and nothing bounds how many there are. {@link
 * #PRE_SIGN_IN_IDLE_LIMIT} bounds how long each one lives instead. A completed sign-in gives the
 * session back the configured limit ({@code GatewaySecurityConfiguration}'s success handler).
 *
 * <p>Only a session this repository causes to exist is shortened. One that already existed, such
 * as a signed-in session starting a sign-in again, keeps the limit it has.
 */
final class PreSignInAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    /**
     * How long a session holding only a started sign-in may sit idle: the time the owner has at
     * Keycloak's pages between leaving the gateway and coming back to its callback. Long enough for
     * a first sign-in, which changes the password, enrols TOTP and fills in the profile; half a
     * signed-in session's limit. It is defence in depth: {@link LoginEntryNavigationFilter} already
     * refuses every browser request but a top-level navigation before it creates one. A sign-in that
     * takes longer fails at the callback and is started again.
     */
    static final Duration PRE_SIGN_IN_IDLE_LIMIT = Duration.ofMinutes(15);

    private final HttpSessionOAuth2AuthorizationRequestRepository sessions =
            new HttpSessionOAuth2AuthorizationRequestRepository();

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return sessions.loadAuthorizationRequest(request);
    }

    /**
     * Saves the request in the session, creating the session if there is none, and gives a session
     * created here {@link #PRE_SIGN_IN_IDLE_LIMIT}.
     *
     * @param authorizationRequest the request to save, or {@code null} to remove the saved one
     */
    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request, HttpServletResponse response) {
        boolean createsTheSession = request.getSession(false) == null;
        sessions.saveAuthorizationRequest(authorizationRequest, request, response);
        if (createsTheSession && authorizationRequest != null) {
            request.getSession().setMaxInactiveInterval(Math.toIntExact(PRE_SIGN_IN_IDLE_LIMIT.toSeconds()));
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        return sessions.removeAuthorizationRequest(request, response);
    }
}
