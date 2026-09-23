package io.github.naimelijah.finassist.gateway.composition;

/**
 * Every path the gateway serves itself, in one place.
 *
 * <p>All of them sit under {@code /bff/}, so that none can ever collide with a path relayed to the
 * ledger or the assistant (ADR-030). The security chain, the login and callback endpoints and
 * {@link SessionController} all name these constants, and each is derived from the one above it
 * rather than spelled again, so a path cannot be changed in one place and missed in another — a
 * renamed callback that the security rules still permitted under its old name would leave a
 * sign-in unable to complete and an old path open.
 */
final class BffRoutes {

    /** The prefix the web client's dev proxy forwards to this gateway. */
    private static final String BFF = "/bff";

    /** Base URI of the login entry. Spring appends the registration id, giving {@link #LOGIN_ENTRY}. */
    static final String AUTHORIZATION_BASE = BFF + "/oauth2/authorization";

    /**
     * Where the browser navigates to start a sign-in, and the {@code loginPage} the chain names.
     * Never redirected to: the gateway answers an unauthenticated request 401 and lets the web
     * client decide when to send someone here (ADR-030).
     */
    static final String LOGIN_ENTRY = AUTHORIZATION_BASE + "/" + KeycloakRegistration.REGISTRATION_ID;

    /** Base URI of the callback Keycloak sends the authorization code to, with the registration id appended. */
    private static final String CALLBACK_BASE = BFF + "/login/oauth2/code";

    /**
     * The pattern the redirection endpoint matches. A wildcard rather than the exact path because
     * that is the shape Spring's redirection endpoint expects: it matches on the registration id
     * in the final segment.
     */
    static final String CALLBACK_PATTERN = CALLBACK_BASE + "/*";

    /** The absolute callback the {@code ClientRegistration} asks Keycloak to send the browser back to. */
    static final String CALLBACK_PATH = CALLBACK_BASE + "/" + KeycloakRegistration.REGISTRATION_ID;

    /**
     * The login entry as the security rules permit it: one segment, the registration id, and
     * nothing deeper. Matches {@link #LOGIN_ENTRY} without repeating the id.
     */
    static final String LOGIN_ENTRY_PATTERN = AUTHORIZATION_BASE + "/*";

    /** The signed-in user as the web client sees them. */
    static final String ME = BFF + "/me";

    /** Ends the gateway's session and answers the URL that ends Keycloak's. POST only, because CSRF is on. */
    static final String LOGOUT = BFF + "/logout";

    private BffRoutes() {}
}
