package io.github.naimelijah.finassist.gateway.composition;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;

/**
 * The Keycloak client registration, written in Java rather than discovered, and the manager that
 * keeps its access token fresh (ADR-030, ADR-031).
 *
 * <p><b>Why no discovery.</b> A service that fetches {@code /.well-known/openid-configuration} at
 * start-up cannot start while Keycloak is down, and the URLs it learns are whichever host name it
 * happened to ask by. Every URL is stated here instead, which also lets them be split — the point
 * of this class.
 *
 * <p><b>Why the URLs are split.</b> Keycloak has two addresses under Compose: the browser reaches
 * it at {@code GATEWAY_OIDC_PUBLIC_URL}, and the gateway's own HTTP client reaches it at
 * {@code GATEWAY_OIDC_INTERNAL_URL}, which is a container name the browser cannot resolve. So the
 * authorization and end-session URIs, which the browser navigates to, are public, while the token
 * and JWK set URIs, which only the gateway calls, use the backchannel. The {@code issuerUri} is
 * the <em>public</em> one, because that is what Keycloak puts in every token's {@code iss} claim
 * and what {@code OidcIdTokenValidator} compares an ID token against. Running natively, both
 * variables hold the same value and the split costs nothing.
 *
 * <p>There is deliberately no user-info URI: with none set, Spring Security skips the UserInfo
 * call altogether and takes the principal's claims from the ID token, which is all {@code
 * SessionController} needs.
 *
 * <p>{@code GATEWAY_CLIENT_SECRET} has no default anywhere, so an unset one is an unresolvable
 * placeholder and the gateway refuses to start rather than failing at the first sign-in.
 */
@Configuration
public class KeycloakRegistration {

    /**
     * The registration id, which appears in the login entry, the callback path and Keycloak's
     * client configuration. Changing it changes a URL the realm has to match.
     */
    static final String REGISTRATION_ID = "keycloak";

    /** The Keycloak realm. A constant, not a setting: one realm serves this whole product (ADR-031). */
    static final String REALM = "finassist";

    private static final String REALM_BASE = "/realms/" + REALM;
    private static final String OIDC_BASE = REALM_BASE + "/protocol/openid-connect";

    /**
     * The metadata member {@link SpaLogoutSuccessHandler} reads to build its end-session URL. It is
     * the name OpenID Connect Session Management gives the endpoint, and the one discovery would
     * have supplied, so the handler needs no second setting of its own.
     */
    static final String END_SESSION_ENDPOINT = "end_session_endpoint";

    /** The confidential client this gateway signs in as. Matches the realm's client id (ADR-031). */
    private static final String CLIENT_ID = "finassist-gateway";

    /**
     * {@code openid} makes it an OIDC request; {@code profile} carries {@code preferred_username},
     * which is the principal's name. No scope grants anything: what the owner may do is each
     * service's own decision (ADR-032).
     */
    private static final String[] SCOPES = {"openid", "profile"};

    /**
     * The client registration, held in memory because there is exactly one and it is fixed at
     * build time.
     *
     * @param publicUrl browser-facing Keycloak base URL, {@code GATEWAY_OIDC_PUBLIC_URL}
     * @param internalUrl backchannel Keycloak base URL, {@code GATEWAY_OIDC_INTERNAL_URL}
     * @param webOrigin the origin the browser reaches FinAssist through,
     *     {@code GATEWAY_PUBLIC_URL}; the callback is absolute so that nothing depends on a
     *     forwarded header (ADR-030)
     * @param clientSecret {@code GATEWAY_CLIENT_SECRET}; absent, the context fails to start
     * @return a repository holding the one {@code keycloak} registration
     */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${finassist.oidc.public-url}") String publicUrl,
            @Value("${finassist.oidc.internal-url}") String internalUrl,
            Origin webOrigin,
            @Value("${finassist.oidc.client-secret}") String clientSecret) {
        return new InMemoryClientRegistrationRepository(
                keycloak(new Origin(publicUrl), new Origin(internalUrl), webOrigin, clientSecret));
    }

    /**
     * Builds the registration. Package-private so {@code KeycloakRegistrationTest} can assert the
     * split without standing a context up.
     */
    static ClientRegistration keycloak(
            Origin browserFacing, Origin backchannel, Origin webOrigin, String clientSecret) {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(CLIENT_ID)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(webOrigin.path(BffRoutes.CALLBACK_PATH))
                .scope(SCOPES)
                .issuerUri(browserFacing.path(REALM_BASE))
                .authorizationUri(browserFacing.path(OIDC_BASE + "/auth"))
                .tokenUri(backchannel.path(OIDC_BASE + "/token"))
                .jwkSetUri(backchannel.path(OIDC_BASE + "/certs"))
                .providerConfigurationMetadata(Map.of(END_SESSION_ENDPOINT, browserFacing.path(OIDC_BASE + "/logout")))
                .userNameAttributeName(StandardClaimNames.PREFERRED_USERNAME)
                .clientSettings(ClientRegistration.ClientSettings.builder()
                        .requireProofKey(true)
                        .build())
                .clientName(CLIENT_ID)
                .build();
    }

    /**
     * Where the tokens live: the servlet session, and nowhere else. Declared rather than left to
     * Spring's fallback, because "the browser never holds a token" is the whole point of this
     * gateway (ADR-030) and the storage choice is what delivers it. Ending the session therefore
     * discards the tokens with it.
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /**
     * Turns a session into a usable access token for a relayed request, refreshing an expired one
     * first.
     *
     * <p>Only two grants are configured: {@code authorization_code}, which adopts the token the
     * login flow already obtained, and {@code refresh_token}. There is deliberately no
     * {@code client_credentials} provider — the gateway acts for the signed-in owner and never for
     * itself. Nothing relays yet; the manager is declared with the login it belongs to so that the
     * task adding the routes wires a manager whose grants were decided here.
     *
     * <p>Refresh-token rotation stays off in the realm: the accounts screen refreshes in parallel
     * and Spring does not serialise refreshes for one session, so rotation would sign the owner out
     * at random (ADR-030).
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrations, OAuth2AuthorizedClientRepository authorizedClients) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build();
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }
}
