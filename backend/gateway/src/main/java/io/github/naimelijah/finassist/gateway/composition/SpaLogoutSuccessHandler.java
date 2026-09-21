package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a completed sign-out with {@code 200} and the URL that ends Keycloak's session too
 * (ADR-030).
 *
 * <p>By the time this runs, Spring's logout handlers have already invalidated the session — and
 * with it the {@code HttpSessionOAuth2AuthorizedClientRepository} entry holding the access and
 * refresh tokens — and {@code CookieClearingLogoutHandler} has expired the session cookie. What is
 * left is the second half of RP-initiated logout, which only the browser can perform, because only
 * the browser holds Keycloak's own session cookie.
 *
 * <p>{@code LogoutFilter} reads the {@link Authentication} before it runs those handlers and passes
 * it here, which is what still makes the ID token reachable. The ID token is the one token that
 * deliberately does reach the browser: {@code id_token_hint} is how Keycloak knows which session to
 * end, and it is an identity assertion, not a credential for any API. The access and refresh tokens
 * stay in the session that has just been destroyed.
 *
 * <p>A sign-out with no session is not an error. The browser still gets an end-session URL, without
 * the hint — {@code client_id} and {@code post_logout_redirect_uri} are enough for Keycloak to
 * accept it — so a client that lost its session mid-flight can still finish signing out.
 */
final class SpaLogoutSuccessHandler implements LogoutSuccessHandler {

    /** OpenID Connect RP-Initiated Logout 1.0 §2, the three parameters the end-session URL carries. */
    private static final String ID_TOKEN_HINT = "id_token_hint";

    private static final String POST_LOGOUT_REDIRECT_URI = "post_logout_redirect_uri";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClientRegistration registration;
    private final String postLogoutRedirectUri;

    /**
     * @param registrations the repository holding the {@code keycloak} registration, read once at
     *     construction because it never changes
     * @param webOrigin where the browser lands after Keycloak has ended its session
     */
    SpaLogoutSuccessHandler(ClientRegistrationRepository registrations, Origin webOrigin) {
        this.registration = registrations.findByRegistrationId(KeycloakRegistration.REGISTRATION_ID);
        this.postLogoutRedirectUri = webOrigin.root();
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication commenced)
            throws IOException {
        byte[] body = JSON.writeValueAsBytes(new LogoutResponse(endSessionUrl(commenced)));
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private String endSessionUrl(Authentication commenced) {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(endSessionEndpoint())
                .queryParam(OAuth2ParameterNames.CLIENT_ID, registration.getClientId())
                .queryParam(POST_LOGOUT_REDIRECT_URI, postLogoutRedirectUri);
        if (commenced != null && commenced.getPrincipal() instanceof OidcUser user) {
            url.queryParam(ID_TOKEN_HINT, user.getIdToken().getTokenValue());
        }
        return url.encode().build().toUriString();
    }

    private String endSessionEndpoint() {
        return (String) registration
                .getProviderDetails()
                .getConfigurationMetadata()
                .get(KeycloakRegistration.END_SESSION_ENDPOINT);
    }
}
