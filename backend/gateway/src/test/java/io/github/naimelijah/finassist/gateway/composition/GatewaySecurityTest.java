package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
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
 * cannot cover is the sign-in itself: {@code GatewayLoginFlowIT} drives the code-plus-PKCE login
 * against a fake OpenID provider, and a sign-in against the real realm is checked only by hand,
 * because its first sign-in enrols TOTP (the README's "First sign-in").
 *
 * <p>Every request names a {@code Host} because {@code HostAllowListFilter} still runs first, and a
 * {@code MockHttpServletRequest} sends no {@code Host} header of its own.
 *
 * <p>Wire values are spelled out rather than borrowed from the production constants: they are the
 * contract the web client and Keycloak are configured against, and a test that borrowed them would
 * pass through a change to any of them.
 *
 * <p>Every property an assertion here reads a literal for is pinned below, because {@code
 * application.yml} resolves each from a {@code GATEWAY_*} variable of the process environment, and a
 * developer's shell exporting one would otherwise turn this red, or green, for a reason that is not
 * the code's.
 */
@SpringBootTest(
        properties = {
            "finassist.oidc.client-secret=a-test-client-secret",
            "finassist.web.public-url=http://localhost:5173",
            "finassist.oidc.public-url=http://localhost:8081",
            "finassist.oidc.internal-url=http://localhost:8081",
            "finassist.http.allowed-hosts=localhost,127.0.0.1",
            "finassist.http.cookie-secure=false"
        })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GatewaySecurityTest {

    private static final String ALLOWED_HOST = "localhost";

    private static final String ME = "/bff/me";
    private static final String LOGOUT = "/bff/logout";
    private static final String LOGIN_ENTRY = "/bff/oauth2/authorization/keycloak";

    /**
     * A registration id no {@code ClientRegistration} answers to, distinctive enough that finding
     * it in the log proves the log line came from this request rather than from anywhere else.
     */
    private static final String UNKNOWN_PROVIDER_SEGMENT = "not-a-registered-provider";

    private static final String UNKNOWN_LOGIN_ENTRY = "/bff/oauth2/authorization/" + UNKNOWN_PROVIDER_SEGMENT;

    /**
     * Logged at WARN by this test, in the gateway's own package, after the request: the capture
     * must hold it, or an empty capture would satisfy every {@code doesNotContain} below.
     */
    private static final String CAPTURE_CONTROL_LINE = "gateway-security-test-capture-control";

    private static final String LEDGER_ACCOUNTS = "/ledger/api/v1/accounts";
    private static final String ASSISTANT_POSITION = "/assistant/api/v1/portfolio/holdings/ACME/position";

    private static final String XSRF_COOKIE = "FINASSIST-XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-FINASSIST-XSRF-TOKEN";

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final String SUBJECT = "2b9d1f4e-0000-4000-8000-0123456789ab";
    private static final String USERNAME = "owner";

    @Autowired
    private MockMvc mvc;

    /** Where the access and refresh tokens are kept. Asserted, not assumed — see the test below. */
    @Autowired
    private OAuth2AuthorizedClientRepository authorizedClients;

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
                .andExpect(jsonPath("$.roles", contains("owner")))
                // The member set, not just the members: three and no more, so a fourth carrying a
                // token could not be added and still pass. The named absence says which mistake
                // this is guarding against, since a count alone would not.
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.idToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
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
     *
     * <p>The whole cookie set is enumerated rather than one cookie read by name, because the claim
     * is "this and nothing else". Reading by name would pass just as happily beside a second cookie
     * carrying a token. Here that set is exactly one: MockMvc is not a servlet container, so no
     * session cookie is written on this response — {@code GatewayApplicationIT} holds that one
     * against a real Tomcat.
     */
    @Test
    void Given_a_signed_in_session_When_the_session_endpoint_is_called_Then_only_a_script_readable_xsrf_cookie_is_set()
            throws Exception {
        MvcResult result = mvc.perform(named(get(ME)).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isOk())
                .andReturn();

        List<Cookie> cookies = List.of(result.getResponse().getCookies());
        assertThat(cookies).extracting(Cookie::getName).containsExactly(XSRF_COOKIE);
        Cookie csrf = cookies.getFirst();
        assertThat(csrf.getValue()).isNotBlank();
        assertThat(csrf.isHttpOnly()).isFalse();
    }

    /**
     * The bean that actually delivers "no token ever reaches the browser". Everything else in this
     * class tests what the browser is handed; this tests where the tokens are kept instead. Swapping
     * it for a cookie-backed store is the one change that would put a refresh token in the browser,
     * and it is the change every other assertion here would sail past.
     */
    @Test
    void Given_the_gateway_When_its_token_store_is_read_Then_the_tokens_live_in_the_server_side_session() {
        assertThat(authorizedClients).isInstanceOf(HttpSessionOAuth2AuthorizedClientRepository.class);
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
     * A method no handler supports is the caller's mistake, and must be answered as one.
     *
     * <p>It reaches the dispatcher rather than the security chain — {@code /bff/me} is permitted
     * for any method, and the CSRF token is sent — so Spring raises {@code
     * HttpRequestMethodNotSupportedException}, which {@code GatewayRelayProblems}' catch-all would
     * otherwise turn into a 500 {@code INTERNAL_ERROR}, logged at ERROR. Nothing failed here but
     * the request.
     */
    @Test
    void Given_a_signed_in_session_When_a_method_no_handler_supports_is_used_Then_it_is_405_not_500() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult signedIn = mvc.perform(
                        named(get(ME)).session(session).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrf = signedIn.getResponse().getCookie(XSRF_COOKIE);
        assertThat(csrf).isNotNull();

        mvc.perform(named(post(ME)).session(session).cookie(csrf).header(XSRF_HEADER, csrf.getValue()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /**
     * The same rule for the other framework refusal this gateway can raise: {@code GET /bff/me}
     * {@code produces} JSON alone, so asking for anything else is 406, not a gateway failure.
     */
    @Test
    void Given_a_signed_in_session_When_a_media_type_no_handler_produces_is_asked_for_Then_it_is_406_not_500()
            throws Exception {
        mvc.perform(named(get(ME)).accept(MediaType.TEXT_PLAIN).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
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
     * The other segment the login-entry pattern admits: one that resolves to no registration at
     * all. {@code OAuth2AuthorizationRequestRedirectFilter} sits ahead of every authorization
     * rule and refuses it itself, so the answer is that filter's failure handler's, not the
     * chain's — and left at Spring's default it is {@code sendError(500)}, Boot's error body, and
     * a stack trace at WARN carrying the segment the caller chose. An unauthenticated caller must
     * not be able to provoke either.
     */
    @Test
    void Given_no_session_When_a_login_entry_naming_an_unknown_provider_is_requested_Then_it_is_refused_401(
            CapturedOutput output) throws Exception {
        mvc.perform(named(get(UNKNOWN_LOGIN_ENTRY)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        LoggerFactory.getLogger(GatewaySecurityTest.class).warn(CAPTURE_CONTROL_LINE);

        assertThat(output.getAll())
                .as("the capture holds a WARN line logged in this package")
                .contains(CAPTURE_CONTROL_LINE);
        assertThat(output.getAll())
                .doesNotContain(UNKNOWN_PROVIDER_SEGMENT)
                .doesNotContain("InvalidClientRegistrationIdException")
                // Nothing at all, not merely nothing incriminating: a line per request is itself
                // the log-filling this refusal exists to stop.
                .doesNotContain(LoginEntryFailureHandler.class.getName());
    }

    /**
     * Deny by default, seen from the far side: a signed-in session is not a licence to reach a path
     * no rule mentions. An anonymous caller gets 401 on the same path, because
     * {@code ExceptionTranslationFilter} sends an anonymous principal to the entry point instead.
     *
     * <p>{@code /v3/api-docs} is listed beside an invented path because the gateway really does
     * serve a document there — {@code springdoc.api-docs.enabled} is {@code true}, deliberately —
     * and {@code application.yml} states as fact that the chain refuses it anyway. Nothing but
     * this assertion held that claim: {@code GatewayContractIT} reads the document in process,
     * never over HTTP.
     *
     * <p>The relay prefixes are permitted for {@code GET} and {@code POST} alone, so every other
     * method on them is an unrouted request too, {@code HEAD} and {@code OPTIONS} included — which
     * {@code GatewaySecurityConfiguration} states and only this holds. A valid CSRF token is sent
     * with each, so the refusal is the authorization rule's {@code FORBIDDEN} rather than the
     * CSRF filter's. {@code HEAD}'s answer carries no body to read a code from, so its status is
     * the assertion. An unpermitted {@code /bff/} path and Swagger UI's page complete the set.
     */
    @ParameterizedTest
    @MethodSource("unroutedRequests")
    void Given_a_signed_in_session_When_an_unrouted_path_is_requested_Then_it_is_denied_403(
            HttpMethod method, String path) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult signedIn = mvc.perform(
                        named(get(ME)).session(session).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrf = signedIn.getResponse().getCookie(XSRF_COOKIE);
        assertThat(csrf).isNotNull();

        ResultActions result = mvc.perform(named(request(method, path))
                        .session(session)
                        .cookie(csrf)
                        .header(XSRF_HEADER, csrf.getValue()))
                .andExpect(status().isForbidden());
        if (!HttpMethod.HEAD.equals(method)) {
            result.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    private static Stream<Arguments> unroutedRequests() {
        Stream<Arguments> unroutedPaths = Stream.of(
                Arguments.of(HttpMethod.GET, "/admin"),
                Arguments.of(HttpMethod.GET, "/v3/api-docs"),
                Arguments.of(HttpMethod.GET, "/swagger-ui/index.html"),
                Arguments.of(HttpMethod.GET, "/bff/not-a-route"),
                Arguments.of(HttpMethod.POST, "/bff/not-a-route"));
        Stream<Arguments> unroutedMethods = Stream.of(LEDGER_ACCOUNTS, ASSISTANT_POSITION)
                .flatMap(path -> Stream.of(
                                HttpMethod.DELETE,
                                HttpMethod.PUT,
                                HttpMethod.PATCH,
                                HttpMethod.HEAD,
                                HttpMethod.OPTIONS)
                        .map(method -> Arguments.of(method, path)));
        return Stream.concat(unroutedPaths, unroutedMethods);
    }

    /**
     * The chain permits {@code DispatcherType.ERROR} so that a genuine 500, which the container
     * forwards to {@code /error}, is not answered 403 by {@code denyAll} instead. That permit is by
     * dispatcher type and not by path, and this is the half of it that a comment alone would leave
     * unproven: a request a client makes to {@code /error} itself arrives as
     * {@code DispatcherType.REQUEST} and is refused like any other unrouted path.
     */
    @Test
    void Given_no_session_When_the_error_path_is_requested_directly_Then_it_is_refused() throws Exception {
        mvc.perform(named(get("/error")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
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
