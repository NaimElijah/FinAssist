package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The Keycloak registration's URLs, which are the whole reason it is written in Java (ADR-030).
 *
 * <p>Keycloak answers on two addresses under Compose, and putting the wrong one in the wrong place
 * fails in a way nothing else would catch: a backchannel authorization URI sends the browser to a
 * host it cannot resolve, and a backchannel issuer makes every token look like a forgery. These
 * tests use two obviously different bases so a swapped pair cannot pass.
 *
 * <p>It also pins the start-up refusal when {@code GATEWAY_CLIENT_SECRET} is unset, because the
 * alternative — starting, and failing at the owner's first sign-in — is the failure mode this
 * project's rules exist to prevent.
 */
class KeycloakRegistrationTest {

    private static final Origin BROWSER_FACING = new Origin("http://keycloak.example:8081");
    private static final Origin BACKCHANNEL = new Origin("http://keycloak-internal:8080");
    private static final Origin WEB = new Origin("http://web.example:5173");

    private static final String SECRET = "a-test-client-secret";

    private static ClientRegistration registration() {
        return KeycloakRegistration.keycloak(BROWSER_FACING, BACKCHANNEL, WEB, SECRET);
    }

    @Test
    void Given_two_keycloak_addresses_When_the_registration_is_built_Then_the_browser_facing_uris_are_public() {
        ClientRegistration.ProviderDetails provider = registration().getProviderDetails();

        assertThat(provider.getAuthorizationUri())
                .isEqualTo("http://keycloak.example:8081/realms/finassist/protocol/openid-connect/auth");
        assertThat(provider.getConfigurationMetadata())
                .containsEntry(
                        "end_session_endpoint",
                        "http://keycloak.example:8081/realms/finassist/protocol/openid-connect/logout");
    }

    @Test
    void Given_two_keycloak_addresses_When_the_registration_is_built_Then_the_gateways_own_uris_are_backchannel() {
        ClientRegistration.ProviderDetails provider = registration().getProviderDetails();

        assertThat(provider.getTokenUri())
                .isEqualTo("http://keycloak-internal:8080/realms/finassist/protocol/openid-connect/token");
        assertThat(provider.getJwkSetUri())
                .isEqualTo("http://keycloak-internal:8080/realms/finassist/protocol/openid-connect/certs");
    }

    /**
     * The issuer is the public one because that is what Keycloak writes into every token's
     * {@code iss} claim, and what the two resource servers are configured to expect (ADR-030).
     * Taking it from the backchannel would reject every ID token the gateway received.
     */
    @Test
    void Given_two_keycloak_addresses_When_the_registration_is_built_Then_the_issuer_is_the_public_one() {
        assertThat(registration().getProviderDetails().getIssuerUri())
                .isEqualTo("http://keycloak.example:8081/realms/finassist");
    }

    /**
     * No user-info URI, so Spring Security never calls Keycloak's UserInfo endpoint and reads the
     * claims from the ID token instead — one fewer request per sign-in, and one fewer URL to get
     * wrong.
     */
    @Test
    void Given_the_registration_When_it_is_built_Then_it_names_no_user_info_uri() {
        assertThat(registration().getProviderDetails().getUserInfoEndpoint().getUri())
                .isNull();
    }

    @Test
    void Given_the_web_origin_When_the_registration_is_built_Then_the_callback_is_absolute_against_it() {
        assertThat(registration().getRedirectUri()).isEqualTo("http://web.example:5173/bff/login/oauth2/code/keycloak");
    }

    /**
     * PKCE turns a stolen authorization code into a useless one, and a confidential client still
     * proves who it is when it redeems the code. Both, not either.
     */
    @Test
    void Given_the_registration_When_it_is_built_Then_it_is_a_confidential_client_that_requires_pkce() {
        ClientRegistration keycloak = registration();

        assertThat(keycloak.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(keycloak.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(keycloak.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(keycloak.getScopes()).containsExactlyInAnyOrder("openid", "profile");
    }

    /**
     * A base URL that arrives with a trailing slash must not become {@code //realms}, which is a
     * different path and would 404 at Keycloak.
     */
    @Test
    void Given_a_base_url_with_a_trailing_slash_When_a_path_is_appended_Then_the_slash_is_not_doubled() {
        assertThat(new Origin("http://keycloak.example:8081/").path("/realms/finassist"))
                .isEqualTo("http://keycloak.example:8081/realms/finassist");
    }

    /**
     * Every URL the gateway hands out is absolute, so a relative or unusable one is refused where
     * it is configured rather than becoming a redirect that resolves against whatever host the
     * request happened to name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"localhost:5173", "/", "ftp://localhost:5173", "http://"})
    void Given_a_public_url_that_is_not_absolute_When_an_origin_is_built_Then_it_is_refused(String url) {
        assertThatThrownBy(() -> new Origin(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    /**
     * {@code GATEWAY_CLIENT_SECRET} has no default in {@code application.yml}, so an unset one is
     * an unresolvable placeholder and the context never finishes refreshing. The whole application
     * is started here, rather than the configuration class alone, because it is {@code
     * application.yml} that decides there is no default and that is what this pins.
     */
    @Test
    void Given_no_client_secret_When_the_gateway_starts_Then_it_refuses_to_start() {
        SpringApplicationBuilder gateway = new SpringApplicationBuilder(GatewayApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .properties("server.port=0");

        assertThatThrownBy(gateway::run).hasStackTraceContaining("GATEWAY_CLIENT_SECRET");
    }
}
