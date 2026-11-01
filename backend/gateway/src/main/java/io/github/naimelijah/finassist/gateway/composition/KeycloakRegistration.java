package io.github.naimelijah.finassist.gateway.composition;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;

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
 *
 * <p><b>Why three HTTP clients are built here rather than left to Spring Security.</b> Every call
 * this gateway makes to Keycloak — the authorization-code exchange, the {@code refresh_token}
 * grant, and the JWK-set fetch that verifies an ID token's signature — must be bounded, or a
 * Keycloak that accepts a connection and then never answers parks a Tomcat thread for ever while
 * the browser spins with no error. Security's own defaults are unbounded here: {@code
 * AbstractRestClientOAuth2AccessTokenResponseClient} builds its client from a static {@code
 * RestClient.builder()}, and {@code OidcIdTokenDecoderFactory} from {@code
 * NimbusJwtDecoder.withJwkSetUri(..)}'s default {@code RestTemplate}. Neither consults Boot's
 * auto-configured builder, so neither sees {@code spring.http.clients.*} — the budget the relay's
 * own client, which Spring Cloud Gateway does build from Boot's builder, has always had. Each is
 * therefore rebuilt below from Boot's builders, so one setting bounds every outbound call this
 * process makes. {@code GatewayKeycloakTimeoutIT} proves all three.
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
        return new InMemoryClientRegistrationRepository(keycloak(
                new Origin(Origin.Setting.GATEWAY_OIDC_PUBLIC_URL, publicUrl),
                new Origin(Origin.Setting.GATEWAY_OIDC_INTERNAL_URL, internalUrl),
                webOrigin,
                clientSecret));
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
     * The client that redeems an authorization code for tokens, bounded by Boot's HTTP settings.
     *
     * <p>Wired explicitly by {@code GatewaySecurityConfiguration} into {@code
     * oauth2Login().tokenEndpoint(..)} rather than left to be discovered by type: the discovery
     * exists, but a bean silently stopping being found — a changed generic, a renamed type — would
     * restore the unbounded default without anything failing to start.
     *
     * @param restClients Boot's auto-configured builder, carrying {@code
     *     spring.http.clients.connect-timeout} and {@code read-timeout}
     */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient(
            RestClient.Builder restClients) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(tokenEndpointClient(restClients));
        return client;
    }

    /**
     * A token-endpoint client in the shape Spring Security's own default has, built on Boot's
     * builder so that it is bounded.
     *
     * <p>The two converters and the error handler are exactly what {@code
     * AbstractRestClientOAuth2AccessTokenResponseClient}'s field initialiser installs: a form
     * request body out, an OAuth 2.0 access-token response in, and an error handler that turns
     * Keycloak's {@code error}/{@code error_description} body into an {@code OAuth2Error} rather
     * than a bare status. Dropping any of them would break the exchange, not merely its
     * diagnostics.
     */
    private static RestClient tokenEndpointClient(RestClient.Builder restClients) {
        return restClients
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    /**
     * Verifies an ID token's signature against Keycloak's JWK set, over a bounded HTTP client.
     *
     * <p>Replaces {@code OidcIdTokenDecoderFactory}, which is {@code final} and exposes no hook
     * for the {@code RestOperations} its {@code NimbusJwtDecoder} fetches keys with. Everything
     * else it does is reproduced here from its own source: RS256 only, its default claim-type
     * converter, and its default validator — Spring's standard set plus {@link
     * OidcIdTokenValidator}, which is what checks the ID token's {@code iss}, {@code aud},
     * {@code azp}, {@code exp} and {@code iat}. It does not check the {@code nonce}: {@code
     * OidcAuthorizationCodeAuthenticationProvider} compares that itself, after this decoder has
     * run. Losing any of these would be a security regression, not a behaviour change, which is
     * why they are named rather than approximated.
     *
     * <p>The decoder is built per registration, as the factory contract requires, but the bounded
     * {@link RestOperations} is shared: it is stateless, and building one per sign-in would
     * discard its connection pooling for nothing.
     *
     * @param restTemplates Boot's auto-configured builder, carrying the same budget as above
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(RestTemplateBuilder restTemplates) {
        RestOperations bounded = restTemplates.build();
        return registration -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
                            registration.getProviderDetails().getJwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(bounded)
                    .build();
            decoder.setJwtValidator(
                    JwtValidators.createDefaultWithValidators(List.of(new OidcIdTokenValidator(registration))));
            decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
            return decoder;
        };
    }

    /**
     * Turns a session into a usable access token for a relayed request, refreshing an expired one
     * first.
     *
     * <p>Only two grants are configured: {@code authorization_code}, which adopts the token the
     * login flow already obtained, and {@code refresh_token}. There is deliberately no
     * {@code client_credentials} provider — the gateway acts for the signed-in owner and never for
     * itself. The relay routes' {@code TokenRelay=} filter looks this manager up from the
     * context, so the grants a relayed request can use are decided here, beside the login they
     * belong to.
     *
     * <p>Refresh-token rotation stays off in the realm: the accounts screen refreshes in parallel
     * and Spring does not serialise refreshes for one session, so rotation would sign the owner out
     * at random (ADR-030).
     *
     * <p>The {@code refresh_token} provider is given its own bounded client for the same reason
     * the authorization-code one is: a refresh happens on a relayed request, on a Tomcat thread a
     * browser is waiting on, so an unbounded one is the worst of the three to leave unfixed.
     *
     * @param restClients Boot's auto-configured builder, carrying the outbound budget
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            RestClient.Builder restClients) {
        RestClientRefreshTokenTokenResponseClient refreshTokens = new RestClientRefreshTokenTokenResponseClient();
        refreshTokens.setRestClient(tokenEndpointClient(restClients));
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(grant -> grant.accessTokenResponseClient(refreshTokens))
                .build();
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }
}
