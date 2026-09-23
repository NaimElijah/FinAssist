package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * The two ways {@link SpaLogoutSuccessHandler} refuses to be built rather than fail every
 * sign-out with a null end-session URL (ADR-030). Both were left untested when the class was
 * introduced; the class is package-private with a package-visible constructor, which is what
 * makes testing them directly this cheap.
 */
class SpaLogoutSuccessHandlerTest {

    private static final Origin WEB_ORIGIN = new Origin("http://web.example:5173");

    @Test
    void Given_no_registration_named_keycloak_When_constructed_Then_it_throws() {
        ClientRegistrationRepository noKeycloak = registrationId -> null;

        assertThatThrownBy(() -> new SpaLogoutSuccessHandler(noKeycloak, WEB_ORIGIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KeycloakRegistration.REGISTRATION_ID);
    }

    @Test
    void Given_a_keycloak_registration_with_no_end_session_endpoint_When_constructed_Then_it_throws() {
        ClientRegistration withoutEndSession = ClientRegistration.withRegistrationId(
                        KeycloakRegistration.REGISTRATION_ID)
                .clientId("finassist-gateway")
                .clientSecret("a-test-client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://web.example:5173/bff/login/oauth2/code/keycloak")
                .authorizationUri("http://keycloak.example:8081/realms/finassist/protocol/openid-connect/auth")
                .tokenUri("http://keycloak.example:8081/realms/finassist/protocol/openid-connect/token")
                .build();
        ClientRegistrationRepository repository = new InMemoryClientRegistrationRepository(withoutEndSession);

        assertThatThrownBy(() -> new SpaLogoutSuccessHandler(repository, WEB_ORIGIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KeycloakRegistration.END_SESSION_ENDPOINT);
    }
}
