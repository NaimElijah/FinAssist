package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What the gateway's security chain guarantees, driven through the chain itself (ADR-030).
 *
 * <p>Every claim here is one an attacker or a mistake could take away: that an unauthenticated
 * request is told so rather than redirected, that a write without its CSRF token is refused, that
 * a path nobody wrote a rule for is denied, that the token the browser does hold is the CSRF one
 * and not a credential, and that sign-out really ends the session.
 *
 * <p>A real context, not a slice: the chain is the thing under test and a slice would load a
 * different one. There is no Keycloak, so {@code oidcLogin()} stands in for a completed sign-in —
 * it builds the same {@code OAuth2AuthenticationToken} the login flow would have stored. What that
 * cannot cover is the sign-in itself; a later task drives a full login against a real realm.
 *
 * <p>Every request names a {@code Host} because {@code HostAllowListFilter} still runs first, and a
 * {@code MockHttpServletRequest} sends no {@code Host} header of its own.
 *
 * <p>Wire values are spelled out rather than borrowed from the production constants: they are the
 * contract the web client and Keycloak are configured against, and a test that borrowed them would
 * pass through a change to any of them.
 */
@SpringBootTest(properties = "finassist.oidc.client-secret=a-test-client-secret")
@AutoConfigureMockMvc
class GatewaySecurityTest {

    private static final String ALLOWED_HOST = "localhost";

    private static final String ME = "/bff/me";
    private static final String LOGOUT = "/bff/logout";
    private static final String LOGIN_ENTRY = "/bff/oauth2/authorization/keycloak";
    private static final String LEDGER_ACCOUNTS = "/ledger/api/v1/accounts";

    private static final String XSRF_COOKIE = "FINASSIST-XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-FINASSIST-XSRF-TOKEN";

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final String SUBJECT = "2b9d1f4e-0000-4000-8000-0123456789ab";
    private static final String USERNAME = "owner";

    @Autowired
    private MockMvc mvc;

    /** The {@code Host} every request names, so the allow-list in front of the chain lets it by. */
    private static MockHttpServletRequestBuilder named(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.HOST, ALLOWED_HOST);
    }

    /** A completed sign-in whose ID token carries the claims this gateway reads. */
    private static Consumer<OidcIdToken.Builder> ownerToken(List<String> roles) {
        return token ->
                token.subject(SUBJECT).claim("preferred_username", USERNAME).claim("roles", roles);
    }

    @ParameterizedTest
    @ValueSource(strings = {ME, LEDGER_ACCOUNTS})
    void Given_no_session_When_a_protected_path_is_requested_Then_it_answers_401_problem_and_never_a_redirect(
            String path) throws Exception {
        mvc.perform(named(get(path)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    /**
     * Spring generates a {@code /login} page listing every provider whenever a login configurer has
     * no {@code loginPage} of its own. Setting one suppresses it; this is the assertion that would
     * notice if that setting were dropped, because the page would answer 200 with HTML from a
     * filter that runs before any authorization rule.
     */
    @Test
    void Given_the_gateway_When_the_generated_login_page_is_requested_Then_no_page_exists_and_it_is_refused()
            throws Exception {
        mvc.perform(named(get("/login")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void Given_a_signed_in_session_When_the_session_endpoint_is_called_Then_it_answers_the_subject_name_and_roles()
            throws Exception {
        mvc.perform(named(get(ME)).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.subject").value(SUBJECT))
                .andExpect(jsonPath("$.name").value(USERNAME))
                .andExpect(jsonPath("$.roles", contains("owner")));
    }

    /**
     * A role the realm defined for something else must not reach the web client as if FinAssist
     * knew it, and a {@code roles} claim that is not a list is the realm's mistake, not a reason to
     * fail a request the services will authorise for themselves anyway (ADR-032).
     */
    @Test
    void Given_a_token_with_an_unknown_role_When_the_session_endpoint_is_called_Then_only_known_roles_are_reported()
            throws Exception {
        mvc.perform(named(get(ME)).with(oidcLogin().idToken(ownerToken(List.of("realm-admin", "viewer")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("viewer")));
    }

    @Test
    void Given_a_token_whose_roles_claim_is_not_a_list_When_the_session_endpoint_is_called_Then_no_role_is_reported()
            throws Exception {
        mvc.perform(named(get(ME))
                        .with(oidcLogin()
                                .idToken(token -> token.subject(SUBJECT)
                                        .claim("preferred_username", USERNAME)
                                        .claim("roles", "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", empty()));
    }

    /**
     * The CSRF token is the one thing a script on the page is meant to read, which is what makes
     * the defence work: a cross-site page can cause a request carrying the session cookie, but it
     * cannot read a cookie belonging to another origin to copy into a header.
     */
    @Test
    void Given_a_signed_in_session_When_the_session_endpoint_is_called_Then_a_script_readable_xsrf_cookie_is_set()
            throws Exception {
        MvcResult result = mvc.perform(named(get(ME)).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie csrf = result.getResponse().getCookie(XSRF_COOKIE);
        assertThat(csrf).isNotNull();
        assertThat(csrf.getValue()).isNotBlank();
        assertThat(csrf.isHttpOnly()).isFalse();
    }

    @Test
    void Given_a_signed_in_session_When_a_write_arrives_without_the_csrf_header_Then_it_is_refused_403()
            throws Exception {
        mvc.perform(named(post(LOGOUT)).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    /**
     * Sign-out end to end, with the CSRF token taken from the cookie a previous response set and
     * sent back in the renamed header — the exact exchange the web client performs, rather than a
     * post-processor that would prove nothing about the renaming.
     */
    @Test
    void Given_a_signed_in_session_When_it_signs_out_Then_the_session_ends_and_keycloak_is_told_where_to_return()
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult signedIn = mvc.perform(
                        named(get(ME)).session(session).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrf = signedIn.getResponse().getCookie(XSRF_COOKIE);
        assertThat(csrf).isNotNull();

        MvcResult signedOut = mvc.perform(
                        named(post(LOGOUT)).session(session).cookie(csrf).header(XSRF_HEADER, csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath(
                        "$.logoutUrl",
                        startsWith("http://localhost:8081/realms/finassist/protocol/openid-connect/logout?")))
                .andExpect(jsonPath("$.logoutUrl", containsString("client_id=finassist-gateway")))
                .andExpect(jsonPath("$.logoutUrl", containsString("post_logout_redirect_uri=http://localhost:5173/")))
                .andExpect(jsonPath("$.logoutUrl", containsString("id_token_hint=id-token")))
                .andReturn();

        assertThat(session.isInvalid()).isTrue();
        Cookie cleared = signedOut.getResponse().getCookie("FINASSIST_SESSION");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    /**
     * The only redirect the gateway issues towards Keycloak, and it issues it because the browser
     * asked for it by navigating here. Its {@code redirect_uri} is the public one so the callback
     * comes back through the web client's origin, and PKCE is {@code S256} because
     * {@code requireProofKey} is set on the registration.
     */
    @Test
    void Given_the_login_entry_When_the_browser_navigates_to_it_Then_it_redirects_to_keycloak_with_pkce()
            throws Exception {
        MvcResult result = mvc.perform(named(get(LOGIN_ENTRY)))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location)
                .startsWith("http://localhost:8081/realms/finassist/protocol/openid-connect/auth?")
                .contains("client_id=finassist-gateway")
                .contains("code_challenge_method=S256")
                .contains("code_challenge=")
                .contains("redirect_uri=http://localhost:5173/bff/login/oauth2/code/keycloak")
                .contains("scope=openid%20profile");
    }

    /**
     * Deny by default, seen from the far side: a signed-in session is not a licence to reach a path
     * no rule mentions. An anonymous caller gets 401 on the same path, because
     * {@code ExceptionTranslationFilter} sends an anonymous principal to the entry point instead.
     */
    @Test
    void Given_a_signed_in_session_When_an_unrouted_path_is_requested_Then_it_is_denied_403() throws Exception {
        mvc.perform(named(get("/admin")).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * Actuator's probes stay public: Compose and any orchestrator read them before a session could
     * exist, and they carry no business data (ADR-030).
     */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness"})
    void Given_no_session_When_a_health_probe_is_read_Then_it_is_served(String path) throws Exception {
        mvc.perform(named(get(path))).andExpect(status().isOk());
    }
}
