package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.gateway.composition.support.FakeOpenIdProvider;
import io.github.naimelijah.finassist.gateway.composition.support.FakeService;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway's relay, proved end to end against a real, running gateway, a real (fake) OpenID
 * provider and a real (fake) downstream service — none of it mocked away — because the property
 * the gateway exists to guarantee is that an access token leaves this process only where it should
 * and never leaves it where it should not (ADR-030).
 *
 * <p>Every test drives the same code-plus-PKCE login a browser would, through a JDK {@link
 * HttpClient} carrying a {@link CookieManager}: hit the login entry, follow the redirect to the
 * fake provider, and carry the {@code code}/{@code state} pair it hands back to the gateway's own
 * callback — at the gateway's <em>real</em> test address rather than the {@code redirect_uri} the
 * {@code ClientRegistration} declares, {@code http://localhost:5173}, which nothing in this test
 * process is listening on. That mismatch is not a workaround; it proves, rather than assumes,
 * that Spring's {@code OAuth2AuthorizationCodeGrantRequestEntityConverter} and the
 * authorization-response matcher compare only the {@code state} parameter against the one stored
 * in the session-backed {@code AuthorizationRequest} repository — the redirect URI is never
 * compared at the callback, and every sign-in below completes regardless. A Vite dev proxy with
 * {@code changeOrigin: true} would produce exactly this mismatch, and sign-in still completes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "finassist.oidc.client-secret=a-test-client-secret")
@ExtendWith(OutputCaptureExtension.class)
class GatewayLoginFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String WEB_ORIGIN = "http://localhost:5173";
    private static final String SESSION_COOKIE = "FINASSIST_SESSION";

    private static final String XSRF_HEADER = "X-FINASSIST-XSRF-TOKEN";

    /** A relayed write, so a refusal below is refusing the request CSRF exists to stop. */
    private static final String LEDGER_TRANSACTIONS = "/ledger/api/v1/transactions";

    /** What {@code SignInFailureHandler} appends when a sign-in does not complete. */
    private static final String SIGN_IN_FAILED = "/?signin=failed";

    /**
     * Shaped like a real {@code S256} challenge — 43 base64url characters — but derived from no
     * verifier the gateway holds, so only the provider's comparison can tell it apart.
     */
    private static final String FOREIGN_CODE_CHALLENGE = "A".repeat(43);

    /** A bearer the browser supplied itself, which no downstream service may ever be shown. */
    private static final String BROWSER_SUPPLIED_BEARER = "Bearer a-token-the-browser-chose";

    /** A W3C trace id the client picks, so the value asserted downstream can only have come from it. */
    private static final String CLIENT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    /** A disposal request body, shaped like the assistant's, so the relayed POST really carries one. */
    private static final String DISPOSAL_BODY =
            "{\"quantity\":\"10\",\"pricePerUnit\":{\"amount\":\"12.34\",\"currency\":\"USD\"}}";

    private static FakeOpenIdProvider idp;
    private static FakeService ledger;
    private static FakeService assistant;

    @LocalServerPort
    private int port;

    /** The relay routes as the running gateway bound them from {@code application.yml}. */
    @Autowired
    private GatewayMvcProperties routes;

    @BeforeAll
    static void startFakes() throws Exception {
        idp = new FakeOpenIdProvider();
        ledger = new FakeService();
        assistant = new FakeService();
    }

    @AfterAll
    static void stopFakes() {
        idp.close();
        ledger.close();
        assistant.close();
    }

    /**
     * Every property a relay or a login needs, pointed at the fakes above. Registered once,
     * before the context starts, because {@code finassist.relay.*} and {@code finassist.oidc.*}
     * are read by beans built at start-up.
     */
    @DynamicPropertySource
    static void relayAndOidcProperties(DynamicPropertyRegistry registry) {
        registry.add("finassist.oidc.public-url", idp::baseUrl);
        registry.add("finassist.oidc.internal-url", idp::baseUrl);
        registry.add("finassist.relay.ledger-uri", ledger::baseUrl);
        registry.add("finassist.relay.assistant-uri", assistant::baseUrl);
    }

    /**
     * Every test that mutates a fake's shared, mutable state resets it, because the fakes are
     * started once for the whole class and a later test must not inherit an earlier one's
     * configuration.
     *
     * <p>The recorded request is cleared too, so that a test asserting a request never reached a
     * downstream service is reading an empty slot rather than whatever ran before it.
     */
    @AfterEach
    void resetFakes() {
        idp.setIssuerOverride(null);
        idp.setAccessTokenExpiresInSeconds(300);
        idp.stopRefusingRefresh();
        ledger.respondNext(200, "application/json", "{}");
        assistant.respondNext(200, "application/json", "{}");
        ledger.forgetLastRequest();
        assistant.forgetLastRequest();
    }

    private String gatewayUrl(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * The browser's half of every test below, pinned to HTTP/1.1 — which is what a browser speaks
     * to a cleartext server, since none implements the h2c upgrade. Left at the JDK's {@link
     * HttpClient.Version#HTTP_2} default, this client would offer one to the gateway on every
     * request, and {@code HTTP2-Settings} — which Spring Cloud Gateway's hop-by-hop filter does
     * not strip, unlike {@code Upgrade} and {@code Connection} — would arrive downstream having
     * come from here rather than from the relay. Pinned, anything an upgrade offer leaves at a
     * {@link FakeService} can only have been added by the gateway's own outbound client, which is
     * what {@link #assertNoHttp2UpgradeOffer} is about.
     */
    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    private HttpResponse<Void> getFollowingNothing(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> relay(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(gatewayUrl(path)))
                        .timeout(TIMEOUT)
                        // Both sent so that "the header is stripped" has something real to
                        // strip: the CSRF one even though this is a GET the filter would not
                        // otherwise check, and an Authorization header because a page is free to
                        // set one and the relay must never pass it on.
                        .header(XSRF_HEADER, "a-header-that-must-not-reach-the-ledger")
                        .header("Authorization", BROWSER_SUPPLIED_BEARER)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A relayed {@code POST} with no body, carrying the given CSRF token — or, when it is {@code
     * null}, no CSRF header at all, which is what a cross-site page would manage.
     */
    private HttpResponse<String> relayPost(HttpClient client, String path, String csrfToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gatewayUrl(path)))
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (csrfToken != null) {
            request.header(XSRF_HEADER, csrfToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Walks the browser's half of the login: the entry, the fake provider's authorization
     * endpoint, and the callback — sent to this test's real gateway address, which is the
     * redirect-URI mismatch described in the class Javadoc. Returns the callback's raw response so
     * a caller can assert either outcome: a completed sign-in, or a failed one.
     */
    private HttpResponse<Void> completeCallback(HttpClient client) throws Exception {
        return finishLogin(client, startLogin(client));
    }

    /**
     * The first half of a sign-in: the login entry, and the redirect it answers with. Split out so
     * a test can read what the browser holds <em>between</em> the two halves, which is the only
     * moment the pre-sign-in session cookie exists.
     */
    private URI startLogin(HttpClient client) throws Exception {
        HttpResponse<Void> entry = getFollowingNothing(client, gatewayUrl("/bff/oauth2/authorization/keycloak"));
        assertThat(entry.statusCode()).isEqualTo(302);
        URI authorizeUri = URI.create(entry.headers().firstValue("Location").orElseThrow());
        assertThat(queryParam(authorizeUri, "code_challenge_method")).isEqualTo("S256");
        assertThat(queryParam(authorizeUri, "code_challenge")).isNotBlank();
        return authorizeUri;
    }

    /** The second half: the fake provider's authorization endpoint, then the gateway's callback. */
    private HttpResponse<Void> finishLogin(HttpClient client, URI authorizeUri) throws Exception {
        URI response = authorizationResponse(client, authorizeUri);
        return sendCallback(client, response.getPath(), response.getQuery());
    }

    /**
     * The fake provider's authorization endpoint, and the redirect it answers with — the {@code
     * code} and {@code state} pair a browser would then carry to the callback. Split out from
     * {@link #finishLogin} so a test can perturb that pair before it is sent.
     */
    private URI authorizationResponse(HttpClient client, URI authorizeUri) throws Exception {
        HttpResponse<Void> authorized = getFollowingNothing(client, authorizeUri.toString());
        assertThat(authorized.statusCode()).isEqualTo(302);
        assertThat(idp.lastAuthorizationRequestHadPkce()).isTrue();
        URI codeRedirect =
                URI.create(authorized.headers().firstValue("Location").orElseThrow());
        assertThat(codeRedirect.toString()).startsWith(WEB_ORIGIN);
        return codeRedirect;
    }

    /**
     * Sends an authorization response to the gateway's callback at this test's real address rather
     * than the registered {@code redirect_uri} — the mismatch the class javadoc explains.
     *
     * @param query the query as {@link URI#getQuery()} gives it, decoded; the constructor below
     *     re-encodes it, so a caller manipulating it works in decoded values throughout
     */
    private HttpResponse<Void> sendCallback(HttpClient client, String path, String query) throws Exception {
        return getFollowingNothing(client, new URI("http", null, "localhost", port, path, query, null).toString());
    }

    /** That same query with one parameter dropped entirely. */
    private static String without(String query, String name) {
        return Arrays.stream(query.split("&"))
                .filter(pair -> !pair.startsWith(name + "="))
                .collect(Collectors.joining("&"));
    }

    /** That same query with one parameter's value replaced. */
    private static String replacing(String query, String name, String value) {
        return Arrays.stream(query.split("&"))
                .map(pair -> pair.startsWith(name + "=") ? name + "=" + value : pair)
                .collect(Collectors.joining("&"));
    }

    /**
     * A full, successful sign-in, for tests whose point is what happens afterward. PKCE is asserted
     * first: a verifier the fake provider refuses also lands on a failed sign-in's redirect, and
     * this way the failure names the cause rather than the symptom.
     */
    private void signIn(HttpClient client) throws Exception {
        HttpResponse<Void> callback = completeCallback(client);
        assertThat(idp.lastTokenRequestPassedPkce())
                .as("the token request's code_verifier hashes to the authorization request's S256 challenge")
                .isTrue();
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location")).contains(WEB_ORIGIN + "/");
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String sessionCookieValue(HttpClient client) {
        return cookieValue(client, SESSION_COOKIE);
    }

    /**
     * {@code csrf().spa()} resolves and writes the CSRF cookie eagerly on every request through
     * the security chain, so it is already in the cookie jar by the time {@link #signIn} returns
     * — no extra round trip is needed to fetch it before a write.
     */
    private static String csrfTokenValue(HttpClient client) {
        return cookieValue(client, "FINASSIST-XSRF-TOKEN");
    }

    private static String cookieValue(HttpClient client, String name) {
        CookieManager cookies = (CookieManager) client.cookieHandler().orElseThrow();
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie was ever set"));
    }

    @Test
    void Given_a_browser_signing_in_When_it_completes_the_code_flow_Then_pkce_is_sent_and_it_lands_on_the_public_url()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
        }
    }

    @Test
    void
            Given_a_signed_in_session_When_it_calls_the_ledger_route_Then_the_bearer_token_is_relayed_and_cookies_and_the_xsrf_header_and_prefix_are_stripped()
                    throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String accessToken = idp.lastAccessToken();

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(200);
            FakeService.ReceivedRequest received = ledger.lastRequest();
            assertThat(received.path()).isEqualTo("/api/v1/accounts");
            assertThat(received.header("authorization")).isEqualTo("Bearer " + accessToken);
            assertThat(received.headers().get("authorization")).containsExactly("Bearer " + accessToken);
            assertThat(received.hasHeader("cookie")).isFalse();
            assertThat(received.hasHeader("x-finassist-xsrf-token")).isFalse();
        }
    }

    /**
     * Which of the browser's own headers each relay route removes, read from the routes the
     * running gateway bound.
     *
     * <p>A configuration assertion rather than a behavioural one, because the behaviour is masked:
     * {@code TokenRelayFilterFunctions.tokenRelay()} calls {@code HttpHeaders#setBearerAuth},
     * which <em>replaces</em>, so a browser-supplied {@code Authorization} header is overwritten
     * whatever this list says — the test above would pass either way. But that same method falls
     * through to {@code next.handle(request)}, the unmodified request, when the client
     * registration id is null or the client manager does not authorize, and on that path the
     * browser's header would reach the ledger untouched. Removing the header outright is what
     * makes the guarantee the gateway's rather than a library's choice of {@code set} over {@code
     * add}, and this is the only place that choice can be pinned.
     *
     * <p>The set is closed, and both routes are checked, because it is the whole list that is the
     * decision: a removal dropped from one route and not the other is exactly the asymmetry that
     * would otherwise go unnoticed.
     */
    @Test
    void Given_the_shipped_relay_routes_When_their_filters_are_read_Then_each_strips_the_browsers_own_headers() {
        assertThat(routes.getRoutes()).extracting(RouteProperties::getId).containsExactly("ledger", "assistant");

        for (RouteProperties route : routes.getRoutes()) {
            assertThat(removedHeaders(route))
                    .as("headers the %s route removes", route.getId())
                    .containsExactlyInAnyOrder("Cookie", XSRF_HEADER, "Authorization");
        }
    }

    /** Every header named by a {@code RemoveRequestHeader} filter on one route. */
    private static List<String> removedHeaders(RouteProperties route) {
        return route.getFilters().stream()
                .filter(filter -> "RemoveRequestHeader".equals(filter.getName()))
                .flatMap(filter -> filter.getArgs().values().stream())
                .toList();
    }

    /**
     * {@code Method=GET,POST} is a two-value predicate parsed from one comma-separated shortcut
     * argument; every other relay test in this class uses {@code GET} alone, so nothing else here
     * would notice if the {@code POST} half of that predicate quietly stopped matching.
     */
    @Test
    void Given_a_signed_in_session_When_it_posts_to_the_ledger_route_Then_the_method_predicate_matches_too()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String csrfToken = csrfTokenValue(client);

            HttpResponse<String> response = relayPost(client, LEDGER_TRANSACTIONS, csrfToken);

            assertThat(response.statusCode()).isEqualTo(200);
            FakeService.ReceivedRequest received = ledger.lastRequest();
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.path()).isEqualTo("/api/v1/transactions");
        }
    }

    /**
     * The request CSRF exists to stop, refused where it matters: a write to the owner's books.
     *
     * <p>{@code GatewaySecurityTest} refuses a write without its token, but its target is {@code
     * POST /bff/logout} — a request a hostile page gains nothing from. Both relayed {@code POST}s
     * in this class send the token, so nothing here would have noticed {@code
     * ignoringRequestMatchers(LEDGER_API_PATTERN, ..)} being added to the chain — a plausible
     * change, since the route already carries {@code RemoveRequestHeader=X-FINASSIST-XSRF-TOKEN}
     * and someone could reason the header is stripped anyway.
     *
     * <p>The refusal has to happen <em>before</em> the relay, not merely instead of a 200, so the
     * downstream fake is asserted to have received nothing at all.
     */
    @Test
    void Given_a_signed_in_session_When_a_relayed_write_arrives_with_no_csrf_header_Then_it_is_refused_and_not_relayed()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);

            HttpResponse<String> response = relayPost(client, LEDGER_TRANSACTIONS, null);

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("CSRF_TOKEN_INVALID");
            assertThat(ledger.lastRequest()).isNull();
        }
    }

    /**
     * The other half: a header that is present but is not the token in the cookie. A page that
     * cannot read the cookie can still put <em>something</em> in the header, so "the header
     * exists" must not be what the check tests.
     */
    @Test
    void
            Given_a_signed_in_session_When_a_relayed_write_carries_the_wrong_csrf_token_Then_it_is_refused_and_not_relayed()
                    throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String realToken = csrfTokenValue(client);

            HttpResponse<String> response = relayPost(client, LEDGER_TRANSACTIONS, realToken + "-tampered");

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("CSRF_TOKEN_INVALID");
            assertThat(ledger.lastRequest()).isNull();
        }
    }

    /**
     * Session fixation, pinned rather than inherited. Spring's default authentication strategy is
     * {@code ChangeSessionIdAuthenticationStrategy}, and nothing in this gateway's configuration
     * names it — so only an assertion stops a future {@code sessionManagement} customiser from
     * quietly turning it off. A session id handed to the browser <em>before</em> a sign-in must
     * not still be valid after one, or a page that could plant that cookie would be signed in as
     * the owner the moment the owner signed in.
     */
    @Test
    void Given_a_session_started_before_sign_in_When_the_callback_completes_Then_the_session_id_changes()
            throws Exception {
        try (HttpClient client = newClient()) {
            URI authorizeUri = startLogin(client);
            String beforeSignIn = sessionCookieValue(client);

            HttpResponse<Void> callback = finishLogin(client, authorizeUri);
            assertThat(callback.statusCode()).isEqualTo(302);

            assertThat(sessionCookieValue(client)).isNotEqualTo(beforeSignIn);
        }
    }

    /**
     * {@code state} is the only thing binding a callback to the browser that started the flow.
     * This class's own javadoc argues that, and then relies on it: every sign-in here completes at
     * an address the {@code ClientRegistration} never declared, because Spring compares the {@code
     * state} parameter against the session-stored authorization request and nothing else. Nothing
     * tested the comparison itself, so a callback carrying no {@code state} at all is the first
     * case.
     *
     * <p>Without it, {@code OAuth2LoginAuthenticationFilter} does not recognise the request as an
     * authorization response and fails the sign-in before any code is redeemed.
     */
    @Test
    void Given_a_callback_carrying_no_state_When_it_arrives_Then_the_sign_in_fails_and_no_session_is_established()
            throws Exception {
        try (HttpClient client = newClient()) {
            URI response = authorizationResponse(client, startLogin(client));

            HttpResponse<Void> callback =
                    sendCallback(client, response.getPath(), without(response.getQuery(), "state"));

            assertSignInFailed(callback);
            assertNotSignedIn(client);
        }
    }

    /**
     * The same guarantee from the other side: a {@code state} that is present but is not the one
     * this browser's flow stored. It is the login-CSRF case — an attacker who can make the browser
     * visit a callback of their choosing must not be able to complete a sign-in with it.
     */
    @Test
    void Given_a_callback_whose_state_is_not_the_browsers_When_it_arrives_Then_the_sign_in_fails_and_no_session()
            throws Exception {
        try (HttpClient client = newClient()) {
            URI response = authorizationResponse(client, startLogin(client));

            HttpResponse<Void> callback = sendCallback(
                    client, response.getPath(), replacing(response.getQuery(), "state", "a-state-nobody-issued"));

            assertSignInFailed(callback);
            assertNotSignedIn(client);
        }
    }

    /**
     * The same {@code code} and {@code state} used twice, by a browser that did not start the
     * flow. That is the shape replay actually takes: the pair is useless to anyone but the browser
     * holding the session the authorization request was stored in, and the first callback consumes
     * it there.
     *
     * <p>Replayed by the <em>same</em> browser the pair would also fail, but that browser is signed
     * in by then, so "no session is established" could not be asserted of it — which is why the
     * replay is made from a second, cookie-less client.
     */
    @Test
    void Given_a_code_and_state_already_used_When_another_browser_replays_them_Then_the_sign_in_fails_for_it()
            throws Exception {
        try (HttpClient browser = newClient()) {
            URI response = authorizationResponse(browser, startLogin(browser));
            HttpResponse<Void> first = sendCallback(browser, response.getPath(), response.getQuery());
            assertThat(first.statusCode()).isEqualTo(302);
            assertThat(first.headers().firstValue("Location")).hasValue(WEB_ORIGIN + "/");

            try (HttpClient replaying = newClient()) {
                HttpResponse<Void> replay = sendCallback(replaying, response.getPath(), response.getQuery());

                assertSignInFailed(replay);
                assertNotSignedIn(replaying);
            }
        }
    }

    /**
     * The threat PKCE exists for: an authorization request altered on its way through the browser.
     * The challenge the provider then holds is not the one the gateway derived from its verifier,
     * so the code it issues must not be redeemable with that verifier — the provider refuses the
     * exchange and the sign-in fails. Before #101 the fake checked only that both halves arrived,
     * and this sign-in would have succeeded.
     */
    @Test
    void Given_a_code_challenge_altered_on_its_way_to_the_provider_When_the_code_is_exchanged_Then_the_sign_in_fails()
            throws Exception {
        try (HttpClient client = newClient()) {
            URI authorizeUri = startLogin(client);
            URI altered = new URI(
                    authorizeUri.getScheme(),
                    null,
                    authorizeUri.getHost(),
                    authorizeUri.getPort(),
                    authorizeUri.getPath(),
                    replacing(authorizeUri.getQuery(), "code_challenge", FOREIGN_CODE_CHALLENGE),
                    null);

            HttpResponse<Void> callback = finishLogin(client, altered);

            assertThat(idp.lastTokenRequestPassedPkce()).isFalse();
            assertSignInFailed(callback);
            assertNotSignedIn(client);
        }
    }

    /** A sign-in that did not complete: the browser is sent back to the web client, told so. */
    private static void assertSignInFailed(HttpResponse<Void> callback) {
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location")).hasValue(WEB_ORIGIN + SIGN_IN_FAILED);
    }

    /** Nothing was signed in: the one endpoint of the gateway's own that needs a session refuses. */
    private void assertNotSignedIn(HttpClient client) throws Exception {
        assertThat(relay(client, "/bff/me").statusCode()).isEqualTo(401);
    }

    /**
     * The owner's rule is that a correlation id crosses every boundary, and the relay is one. The
     * ledger and the assistant each log a trace id, so a request a person is looking at must be
     * findable in all three services' logs under the same one.
     *
     * <p>The header is not passed through untouched: the gateway continues the trace, so the
     * {@code trace-id} field is the caller's while the {@code parent-id} field is the gateway's
     * own span. Both halves are asserted, because an implementation that simply copied the header
     * would satisfy the first alone and would record no span of its own.
     */
    @Test
    void Given_a_relayed_request_carrying_a_traceparent_When_it_arrives_Then_it_carries_the_same_trace_id()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String traceparent = "00-" + CLIENT_TRACE_ID + "-00f067aa0ba902b7-01";

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(gatewayUrl("/ledger/api/v1/accounts")))
                            .timeout(TIMEOUT)
                            .header("traceparent", traceparent)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            String relayed = ledger.lastRequest().header("traceparent");
            assertThat(relayed).isNotNull();
            assertThat(relayed.split("-")[1]).isEqualTo(CLIENT_TRACE_ID);
            assertThat(relayed).isNotEqualTo(traceparent);
        }
    }

    /**
     * The relay must not try to upgrade a cleartext connection to HTTP/2.
     *
     * <p>The JDK {@code HttpClient} the relay is built on defaults to {@link
     * HttpClient.Version#HTTP_2}, and over cleartext that means an h2c upgrade offer on every
     * request: {@code Upgrade: h2c}, an {@code HTTP2-Settings} header, and a {@code Connection}
     * header naming both. Tomcat ignores them, so the ledger never minded. The assistant runs
     * uvicorn/h11, which logs "Unsupported upgrade request." and then — for an upgrade request
     * that <em>also</em> carries a body — "Invalid HTTP request received." and answers 400. Every
     * POST the gateway relayed to the assistant therefore failed, and pricing a disposal was
     * unusable from the web client.
     *
     * <p>A POST is the case a person sees, so it is asserted against the assistant route this
     * defect was found on. The offer is made on a GET too, which the test below pins, because
     * nothing about it depends on there being a body — only uvicorn's refusal does.
     */
    @Test
    void Given_a_signed_in_session_When_it_posts_a_body_to_the_assistant_route_Then_no_http2_upgrade_is_offered()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String csrfToken = csrfTokenValue(client);

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(gatewayUrl("/assistant/api/v1/portfolio/holdings/ACME/disposal")))
                            .timeout(TIMEOUT)
                            .header(XSRF_HEADER, csrfToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(DISPOSAL_BODY))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            FakeService.ReceivedRequest received = assistant.lastRequest();
            assertThat(received.method()).isEqualTo("POST");
            assertNoHttp2UpgradeOffer(received);
        }
    }

    /** The GET half of the guarantee above: the offer is made with no body to provoke it. */
    @Test
    void Given_a_signed_in_session_When_it_gets_the_ledger_route_Then_no_http2_upgrade_is_offered() throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(200);
            assertNoHttp2UpgradeOffer(ledger.lastRequest());
        }
    }

    /**
     * The three headers a JDK {@code HttpClient} sends together when it offers an h2c upgrade.
     * {@code Connection} is checked by content rather than presence: a relayed request may
     * legitimately carry one, but never one naming {@code Upgrade}.
     */
    private static void assertNoHttp2UpgradeOffer(FakeService.ReceivedRequest received) {
        assertThat(received.hasHeader("upgrade")).as("Upgrade header").isFalse();
        assertThat(received.hasHeader("http2-settings"))
                .as("HTTP2-Settings header")
                .isFalse();
        assertThat(received.header("connection"))
                .as("Connection header")
                .satisfiesAnyOf(
                        connection -> assertThat(connection).isNull(),
                        connection -> assertThat(connection).doesNotContainIgnoringCase("upgrade"));
    }

    @Test
    void Given_a_signed_in_session_When_it_calls_the_assistant_route_Then_it_is_relayed_too() throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String accessToken = idp.lastAccessToken();

            HttpResponse<String> response = relay(client, "/assistant/api/v1/portfolio/holdings/ACME/position");

            assertThat(response.statusCode()).isEqualTo(200);
            FakeService.ReceivedRequest received = assistant.lastRequest();
            assertThat(received.path()).isEqualTo("/api/v1/portfolio/holdings/ACME/position");
            assertThat(received.header("authorization")).isEqualTo("Bearer " + accessToken);
        }
    }

    @Test
    void Given_the_ledger_answers_a_problem_When_relayed_Then_it_passes_through_unchanged() throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            ledger.respondNext(
                    422,
                    "application/problem+json",
                    "{\"type\":\"about:blank\",\"title\":\"Unprocessable Entity\",\"status\":422,"
                            + "\"detail\":\"the category does not exist\",\"code\":\"CATEGORY_NOT_FOUND\"}");

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.headers().firstValue("content-type")).contains("application/problem+json");
            assertThat(response.body()).contains("CATEGORY_NOT_FOUND");
        }
    }

    @Test
    void Given_an_already_expired_access_token_When_a_relay_is_made_Then_it_is_refreshed_before_the_call()
            throws Exception {
        try (HttpClient client = newClient()) {
            idp.setAccessTokenExpiresInSeconds(-120);
            signIn(client);
            String tokenIssuedAtLogin = idp.lastAccessToken();
            idp.setAccessTokenExpiresInSeconds(300);

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(200);
            String refreshedToken = idp.lastAccessToken();
            assertThat(refreshedToken).isNotEqualTo(tokenIssuedAtLogin);
            assertThat(ledger.lastRequest().header("authorization")).isEqualTo("Bearer " + refreshedToken);
        }
    }

    @Test
    void Given_a_refresh_that_keycloak_refuses_When_relayed_Then_the_session_is_invalidated_and_it_answers_401()
            throws Exception {
        try (HttpClient client = newClient()) {
            idp.setAccessTokenExpiresInSeconds(-120);
            signIn(client);
            idp.refuseNextRefresh();

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("UNAUTHENTICATED");

            HttpResponse<String> me = relay(client, "/bff/me");
            assertThat(me.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void
            Given_an_id_token_from_a_foreign_issuer_When_signing_in_Then_it_fails_and_the_browser_is_sent_to_signin_failed()
                    throws Exception {
        try (HttpClient client = newClient()) {
            idp.setIssuerOverride("http://foreign-issuer.example/realms/other");

            HttpResponse<Void> callback = completeCallback(client);

            assertThat(idp.lastTokenRequestPassedPkce())
                    .as("the code exchange itself succeeded, so only the ID token can have failed the sign-in")
                    .isTrue();
            assertThat(callback.statusCode()).isEqualTo(302);
            assertThat(callback.headers().firstValue("Location")).contains(WEB_ORIGIN + "/?signin=failed");
        }
    }

    /**
     * The end-to-end half of the guarantee {@code SignInFailureHandlerTest} already pins at the
     * unit level: nothing this flow logs carries a value that would let a reader impersonate the
     * session or redeem anything on the owner's behalf. A refused refresh is the highest-risk
     * moment in the whole relay, so it is the one this sweep deliberately drives through.
     */
    @Test
    void Given_a_login_and_a_refused_refresh_When_the_logs_are_swept_Then_no_token_code_or_session_value_appears(
            CapturedOutput output) throws Exception {
        try (HttpClient client = newClient()) {
            idp.setAccessTokenExpiresInSeconds(-120);
            idp.refuseNextRefresh();

            HttpResponse<Void> callback = completeCallback(client);
            assertThat(callback.statusCode()).isEqualTo(302);
            String accessToken = idp.lastAccessToken();
            String refreshToken = idp.lastRefreshToken();
            String idToken = idp.lastIdToken();
            String sessionCookie = sessionCookieValue(client);

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");
            assertThat(response.statusCode()).isEqualTo(401);

            String logs = output.getAll();
            assertThat(logs)
                    .doesNotContain(accessToken)
                    .doesNotContain(refreshToken)
                    .doesNotContain(idToken)
                    .doesNotContain(sessionCookie);
        }
    }

    /**
     * A dedicated context so that only this test's ledger URI is unreachable, leaving the outer
     * class's live {@code ledger} and {@code assistant} fakes untouched for every other test.
     */
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "finassist.oidc.client-secret=a-test-client-secret")
    class LedgerDown {

        @LocalServerPort
        private int downPort;

        /**
         * A port reserved and immediately released: nothing listens on it for the life of this
         * nested context, so a connection to it is refused rather than merely slow.
         */
        @DynamicPropertySource
        static void deadLedger(DynamicPropertyRegistry registry) throws Exception {
            int deadPort;
            try (java.net.ServerSocket reserved = new java.net.ServerSocket(0)) {
                deadPort = reserved.getLocalPort();
            }
            registry.add("finassist.oidc.public-url", idp::baseUrl);
            registry.add("finassist.oidc.internal-url", idp::baseUrl);
            registry.add("finassist.relay.ledger-uri", () -> "http://localhost:" + deadPort);
            registry.add("finassist.relay.assistant-uri", assistant::baseUrl);
        }

        private String url(String path) {
            return "http://localhost:" + downPort + path;
        }

        @Test
        void Given_the_ledger_is_unreachable_When_relayed_Then_it_answers_502_upstream_unavailable() throws Exception {
            try (HttpClient client = newClient()) {
                HttpResponse<Void> entry = getFollowingNothing(client, url("/bff/oauth2/authorization/keycloak"));
                URI authorizeUri =
                        URI.create(entry.headers().firstValue("Location").orElseThrow());
                HttpResponse<Void> authorized = getFollowingNothing(client, authorizeUri.toString());
                URI codeRedirect =
                        URI.create(authorized.headers().firstValue("Location").orElseThrow());
                URI realCallback = new URI(
                        "http", null, "localhost", downPort, codeRedirect.getPath(), codeRedirect.getQuery(), null);
                HttpResponse<Void> callback = getFollowingNothing(client, realCallback.toString());
                assertThat(callback.statusCode()).isEqualTo(302);

                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url("/ledger/api/v1/accounts")))
                                .timeout(TIMEOUT)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(502);
                assertThat(response.body()).contains("UPSTREAM_UNAVAILABLE");
            }
        }
    }
}
