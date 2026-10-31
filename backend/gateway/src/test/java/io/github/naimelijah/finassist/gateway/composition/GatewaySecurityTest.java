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

import io.github.naimelijah.finassist.gateway.composition.support.BackendContracts;
import io.github.naimelijah.finassist.gateway.composition.support.BackendContracts.Operation;
import io.github.naimelijah.finassist.gateway.composition.support.FakeService;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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

    /** Spring's default name for the CSRF request parameter, which the gateway must not read. */
    private static final String CSRF_PARAMETER = "_csrf";

    /** The Fetch Metadata headers a browser sends with every request, and their values for a fetch. */
    private static final String SEC_FETCH_MODE = "Sec-Fetch-Mode";

    private static final String SEC_FETCH_DEST = "Sec-Fetch-Dest";

    private static final String CORS = "cors";

    private static final String EMPTY = "empty";

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final String SUBJECT = "2b9d1f4e-0000-4000-8000-0123456789ab";
    private static final String USERNAME = "owner";

    /**
     * Each relay prefix the web client calls, and the committed contract of the service behind it.
     * Spelled out, like every wire value here.
     */
    private static final Map<String, String> CONTRACT_BY_RELAY_PREFIX =
            Map.of("/ledger", "ledger-v1.openapi.json", "/assistant", "assistant-v1.openapi.json");

    /**
     * Stands in for the ledger and the assistant both, answering {@code 200} to every request. A
     * relayed call therefore never leaves loopback, and a {@code 401} or {@code 403} can only be the
     * gateway's own answer, never an upstream's passed through.
     */
    private static FakeService upstream;

    /**
     * The body {@link #upstream} answers with, set rather than left to its default, and distinctive
     * enough that no gateway answer could carry it: a relayed request must come back with it.
     */
    private static final String UPSTREAM_BODY = "{\"answeredBy\":\"upstream\"}";

    /**
     * The registration a real sign-in completes against. {@code oidcLogin()} defaults to one named
     * {@code test}, which {@code TokenRelay} cannot authorize, so a relayed request would end in a
     * 500 before ever reaching {@link #upstream}.
     */
    private static final String REGISTRATION_ID = "keycloak";

    @Autowired
    private ClientRegistrationRepository registrations;

    @Autowired
    private MockMvc mvc;

    /** Where the access and refresh tokens are kept. Asserted, not assumed — see the test below. */
    @Autowired
    private OAuth2AuthorizedClientRepository authorizedClients;

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = new FakeService();
        upstream.respondNext(HttpStatus.OK.value(), MediaType.APPLICATION_JSON_VALUE, UPSTREAM_BODY);
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    /** Points both relay routes at {@link #upstream}; read by the route beans built at start-up. */
    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("finassist.relay.ledger-uri", () -> upstream.baseUrl());
        registry.add("finassist.relay.assistant-uri", () -> upstream.baseUrl());
    }

    /** The {@code Host} every request names, so the allow-list in front of the chain lets it by. */
    private static MockHttpServletRequestBuilder named(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.HOST, ALLOWED_HOST);
    }

    /**
     * Delivers a bodiless request the way a servlet container does: with its body stream already
     * finished.
     *
     * <p>Tomcat reports an empty body's stream finished before any read, so the relay sees no body.
     * MockMvc reports the same stream unfinished until it is read, so the relay streams an empty
     * body instead, chunked because it has no length: a request no browser can make through Tomcat,
     * and one that can trip a race in JDK 21's HTTP client, whose {@code NullPointerException} the
     * gateway answers 500 (#358). Reading the stream first lets the relay decide about the body as
     * it does in production. It is for bodiless requests only: a body it read would be lost, so one
     * fails the test rather than reaching upstream empty.
     */
    private static RequestPostProcessor bodyAlreadyRead() {
        return request -> {
            byte[] body;
            try {
                body = request.getInputStream().readAllBytes();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("the mock request body could not be read", unreadable);
            }
            if (body.length > 0) {
                throw new IllegalStateException("bodyAlreadyRead() is for bodiless requests; it would have dropped a "
                        + body.length + "-byte body");
            }
            return request;
        };
    }

    /**
     * Signs {@code session} in and returns the CSRF cookie the gateway set for it, which a write
     * then sends back in the renamed header: the exchange the web client performs.
     */
    private Cookie signIn(MockHttpSession session) throws Exception {
        MvcResult signedIn = mvc.perform(
                        named(get(ME)).session(session).with(oidcLogin().idToken(ownerToken(List.of("owner")))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrf = signedIn.getResponse().getCookie(XSRF_COOKIE);
        assertThat(csrf).isNotNull();
        return csrf;
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
     * The token is read from the renamed header and nowhere else (ADR-030). Spring's default
     * handler also reads a {@code _csrf} request parameter, from the query string or a form body,
     * and either can be put on a request without setting a header. So both places are tried, each
     * with the cookie's value as it is and as Spring's masking handler writes it — the form its
     * parameter path would unmask and accept — and every one must be refused before {@code
     * LogoutFilter} ends the session.
     */
    @ParameterizedTest
    @MethodSource("csrfTokensSentAsAParameter")
    void
            Given_a_signed_in_session_When_logout_carries_the_csrf_token_only_as_a_request_parameter_Then_it_is_refused_403_and_the_session_survives(
                    ParameterPlacement placement, CsrfTokenForm form) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = signIn(session);

        mvc.perform(placement.carrying(named(post(LOGOUT)).session(session).cookie(csrf), form.of(csrf.getValue())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        assertThat(session.isInvalid())
                .as("the session after the refused sign-out")
                .isFalse();
        mvc.perform(named(get(ME)).session(session)).andExpect(status().isOk());
    }

    private static Stream<Arguments> csrfTokensSentAsAParameter() {
        return Stream.of(ParameterPlacement.values())
                .flatMap(placement -> Stream.of(CsrfTokenForm.values()).map(form -> Arguments.of(placement, form)));
    }

    /** Where a request parameter can travel without a header being set. */
    enum ParameterPlacement {
        QUERY {
            @Override
            MockHttpServletRequestBuilder carrying(MockHttpServletRequestBuilder request, String token) {
                return request.queryParam(CSRF_PARAMETER, token);
            }
        },
        FORM_BODY {
            @Override
            MockHttpServletRequestBuilder carrying(MockHttpServletRequestBuilder request, String token) {
                return request.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(CSRF_PARAMETER + "=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
            }
        };

        abstract MockHttpServletRequestBuilder carrying(MockHttpServletRequestBuilder request, String token);
    }

    /** The cookie's value as it is, and as Spring's masking handler would write it. */
    enum CsrfTokenForm {
        RAW {
            @Override
            String of(String cookieValue) {
                return cookieValue;
            }
        },
        MASKED {
            @Override
            String of(String cookieValue) {
                return masked(cookieValue);
            }
        };

        abstract String of(String cookieValue);
    }

    /**
     * {@code cookieValue} under an all-zero mask: the mask, then the value itself, base64url-encoded,
     * which is a value {@code XorCsrfTokenRequestAttributeHandler} unmasks back to the cookie's.
     * Spelled out here because the point is the format, not whichever mask Spring would pick.
     */
    static String masked(String cookieValue) {
        byte[] token = cookieValue.getBytes(StandardCharsets.UTF_8);
        byte[] maskThenToken = new byte[token.length * 2];
        System.arraycopy(token, 0, maskThenToken, token.length, token.length);
        return Base64.getUrlEncoder().encodeToString(maskThenToken);
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
     * the request. RFC 9110 §15.5.6 requires a 405 to name the methods the resource does support,
     * as the ledger's and the assistant's do.
     */
    @Test
    void Given_a_signed_in_session_When_a_method_no_handler_supports_is_used_Then_it_is_405_not_500() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = signIn(session);

        mvc.perform(named(post(ME)).session(session).cookie(csrf).header(XSRF_HEADER, csrf.getValue()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
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
     *
     * <p>So do two Actuator endpoints beside the permitted health one. {@code /actuator/heapdump}
     * would hand over a heap holding every session's tokens and the client secret, and {@code
     * /actuator/env} the configuration, so a health permit widened to {@code /actuator/**} must fail
     * here, whatever the exposure list says at the time.
     */
    @ParameterizedTest
    @MethodSource("unroutedRequests")
    void Given_a_signed_in_session_When_an_unrouted_path_is_requested_Then_it_is_denied_403(
            HttpMethod method, String path) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = signIn(session);

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
                Arguments.of(HttpMethod.GET, "/actuator/heapdump"),
                Arguments.of(HttpMethod.GET, "/actuator/env"),
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
     * The chain's half of the relay's contract (#339): every operation the ledger's and the
     * assistant's committed contracts publish, sent by a signed-in browser with its CSRF token to
     * the gateway path the web client calls, gets past the chain. The chain names its relay
     * methods apart from the routes' {@code Method=} predicates, so widening a route for a new
     * operation without widening the chain would leave that operation refused here, whatever
     * {@code GatewayRelayRoutesTest} says about the route.
     *
     * <p>The chain is what is under test. Its refusals are {@code 401} from the entry point and
     * {@code 403} from the access-denied handler, a CSRF refusal included, and {@link #upstream}
     * answers neither, so either status here is the chain's. Being neither is no proof of
     * passage, though: an exception inside a chain filter answers {@code 500}, and so does a relay
     * that fails after {@link #upstream} has already read the request line and headers. So the
     * answer must be {@link #upstream}'s own, and {@link #upstream} forgets its last request first
     * and must then have received this operation's method at the path with the relay prefix
     * stripped, which only a request that crossed the whole chain can deliver. A route that stopped
     * matching fails here too, as a {@code 404} with nothing received; {@code
     * GatewayRelayRoutesTest} is where that is pinned on its own.
     */
    @ParameterizedTest
    @MethodSource("publishedBackendOperations")
    void Given_a_signed_in_session_When_a_published_backend_operation_is_relayed_Then_the_chain_lets_it_through(
            String relayPrefix, Operation operation) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = signIn(session);
        String path = relayPrefix + operation.concretePath();
        upstream.forgetLastRequest();

        MockHttpServletResponse response = mvc.perform(named(request(operation.method(), path))
                        .session(session)
                        .with(oidcLogin()
                                .clientRegistration(registrations.findByRegistrationId(REGISTRATION_ID))
                                .idToken(ownerToken(List.of("owner"))))
                        .cookie(csrf)
                        .header(XSRF_HEADER, csrf.getValue())
                        .with(bodyAlreadyRead()))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "the status %s %s answered, whose body was %s",
                        operation.method(), path, response.getContentAsString())
                .isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString())
                .as("the body %s %s answered, which must be upstream's own", operation.method(), path)
                .isEqualTo(UPSTREAM_BODY);
        FakeService.ReceivedRequest relayed = upstream.lastRequest();
        assertThat(relayed)
                .as(
                        "the request upstream received for %s %s, which the gateway answered %s with %s",
                        operation.method(), path, response.getStatus(), response.getContentAsString())
                .isNotNull();
        assertThat(relayed.hasHeader(HttpHeaders.TRANSFER_ENCODING))
                .as(
                        "whether %s %s reached upstream as a streamed body, which a bodiless request"
                                + " delivered as Tomcat delivers it never is",
                        operation.method(), path)
                .isFalse();
        assertThat(relayed.method())
                .as("the method upstream received for %s %s", operation.method(), path)
                .isEqualTo(operation.method().name());
        assertThat(relayed.path())
                .as("the path upstream received for %s %s, its relay prefix stripped", operation.method(), path)
                .isEqualTo(operation.concretePath());
    }

    private static Stream<Arguments> publishedBackendOperations() {
        return CONTRACT_BY_RELAY_PREFIX.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(relay -> BackendContracts.operations(BackendContracts.read(relay.getValue())).stream()
                        .map(operation -> Arguments.of(relay.getKey(), operation)));
    }

    /**
     * The browser security headers Spring Security writes by default, pinned because nothing in
     * the gateway's configuration names them and so nothing else would notice them dropped or
     * relaxed. {@code no-store} matters most on {@code /bff/me}, which names the owner, and on the
     * sign-out answer, whose {@code logoutUrl} carries the ID token; a relayed answer whose upstream
     * set none of these headers must carry them too. {@link #upstream} sets none of them.
     */
    @Test
    void
            Given_a_signed_in_session_When_me_logout_and_a_relayed_answer_are_read_Then_each_carries_nosniff_frame_deny_and_no_store()
                    throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = signIn(session);

        MockHttpServletResponse me = mvc.perform(named(get(ME)).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        MockHttpServletResponse relayed = mvc.perform(named(get(LEDGER_ACCOUNTS))
                        .session(session)
                        .with(oidcLogin()
                                .clientRegistration(registrations.findByRegistrationId(REGISTRATION_ID))
                                .idToken(ownerToken(List.of("owner"))))
                        .with(bodyAlreadyRead()))
                .andReturn()
                .getResponse();
        assertThat(relayed.getContentAsString()).as("the relayed answer's body").isEqualTo(UPSTREAM_BODY);
        MockHttpServletResponse signedOut = mvc.perform(
                        named(post(LOGOUT)).session(session).cookie(csrf).header(XSRF_HEADER, csrf.getValue()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        for (Map.Entry<String, MockHttpServletResponse> answer :
                Map.of(ME, me, LEDGER_ACCOUNTS, relayed, LOGOUT, signedOut).entrySet()) {
            MockHttpServletResponse response = answer.getValue();
            assertThat(response.getHeader("X-Content-Type-Options"))
                    .as("X-Content-Type-Options on %s", answer.getKey())
                    .isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options"))
                    .as("X-Frame-Options on %s", answer.getKey())
                    .isEqualTo("DENY");
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                    .as("Cache-Control on %s", answer.getKey())
                    .contains("no-store");
        }
    }

    /**
     * {@code LoginEntryNavigationFilter} looks at Fetch Metadata on the login entry and nowhere
     * else. The web client reads {@code /bff/me} and the relay with {@code fetch}, which the browser
     * marks {@code Sec-Fetch-Mode: cors} and {@code Sec-Fetch-Dest: empty}, so a filter that looked
     * at those headers on any other path would refuse every call the web client makes.
     */
    @Test
    void Given_a_signed_in_session_When_a_cors_fetch_reads_bff_me_or_a_relay_Then_it_is_answered_200()
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        signIn(session);

        mvc.perform(named(get(ME)).session(session).header(SEC_FETCH_MODE, CORS).header(SEC_FETCH_DEST, EMPTY))
                .andExpect(status().isOk());
        MockHttpServletResponse relayed = mvc.perform(named(get(LEDGER_ACCOUNTS))
                        .session(session)
                        .header(SEC_FETCH_MODE, CORS)
                        .header(SEC_FETCH_DEST, EMPTY)
                        .with(oidcLogin()
                                .clientRegistration(registrations.findByRegistrationId(REGISTRATION_ID))
                                .idToken(ownerToken(List.of("owner"))))
                        .with(bodyAlreadyRead()))
                .andReturn()
                .getResponse();

        assertThat(relayed.getStatus()).as("the relayed fetch's status").isEqualTo(HttpStatus.OK.value());
        assertThat(relayed.getContentAsString()).as("the relayed fetch's body").isEqualTo(UPSTREAM_BODY);
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
