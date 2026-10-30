package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.gateway.composition.support.FakeOpenIdProvider;
import io.github.naimelijah.finassist.gateway.composition.support.FakeService;
import java.io.IOException;
import java.net.BindException;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
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
        properties = {
            "finassist.oidc.client-secret=" + GatewayLoginFlowIT.CLIENT_SECRET,
            // WEB_ORIGIN, the plain-HTTP cookie jar and the localhost Host header depend on these,
            // which application.yml would otherwise take from the process environment.
            "finassist.web.public-url=http://localhost:5173",
            "finassist.http.allowed-hosts=localhost,127.0.0.1",
            "finassist.http.cookie-secure=false"
        })
@ExtendWith(OutputCaptureExtension.class)
class GatewayLoginFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String WEB_ORIGIN = "http://localhost:5173";
    private static final String SESSION_COOKIE = "FINASSIST_SESSION";

    private static final String XSRF_HEADER = "X-FINASSIST-XSRF-TOKEN";

    private static final String CSRF_COOKIE = "FINASSIST-XSRF-TOKEN";

    /** A cookie value only an upstream answer carries, so finding it in the browser names its source. */
    private static final String UPSTREAM_COOKIE_VALUE = "a-value-an-upstream-set";

    /** A cookie a service could try to plant: one named like the gateway's own CSRF cookie. */
    private static final String UPSTREAM_SET_COOKIE = CSRF_COOKIE + "=" + UPSTREAM_COOKIE_VALUE + "; Path=/";

    /** A {@code Server} header only an upstream answer carries. */
    private static final String UPSTREAM_SERVER_HEADER = "FakeUpstream/1.0";

    /**
     * What a service could answer that the browser must never receive from it: a cookie, every
     * header both routes strip because it changes the browser's state for the whole origin, and
     * the service's {@code Server}. Neither the gateway nor its container sets any of these but a
     * cookie of its own on a plain-HTTP answer, so each must be absent from what the browser
     * receives.
     */
    private static final Map<String, String> UPSTREAM_ONLY_HEADERS = Map.ofEntries(
            Map.entry("Set-Cookie", UPSTREAM_SET_COOKIE),
            Map.entry("Server", UPSTREAM_SERVER_HEADER),
            Map.entry("Clear-Site-Data", "\"cookies\", \"storage\""),
            Map.entry("Strict-Transport-Security", "max-age=31536000"),
            Map.entry("Access-Control-Allow-Origin", "*"),
            Map.entry("Access-Control-Allow-Credentials", "true"),
            Map.entry("Access-Control-Allow-Methods", "GET, POST, DELETE"),
            Map.entry("Access-Control-Allow-Headers", "*"),
            Map.entry("Access-Control-Allow-Private-Network", "true"),
            Map.entry("Access-Control-Expose-Headers", "*"),
            Map.entry("Access-Control-Max-Age", "600"));

    /** Spring's default name for the CSRF request parameter, which the gateway must not read. */
    private static final String CSRF_PARAMETER = "_csrf";

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

    /**
     * A well-formed h2c settings payload, base64url-encoded as RFC 7540 §3.2.1 has an upgrade send
     * it: MAX_CONCURRENT_STREAMS 100, INITIAL_WINDOW_SIZE 2^30 and ENABLE_PUSH 0.
     */
    private static final String CLIENT_HTTP2_SETTINGS = "AAMAAABkAARAAAAAAAIAAAAA";

    /** A W3C trace id the client picks, so the value asserted downstream can only have come from it. */
    private static final String CLIENT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    /**
     * The gateway's client secret in this context. Spelled once, because the log sweep searches
     * for it as well as configuring it. Not private, because the class's own annotation names it.
     */
    static final String CLIENT_SECRET = "a-test-client-secret";

    /**
     * ADR-030's idle timeout for a signed-in session, {@code server.servlet.session.timeout},
     * spelled here rather than read from the configuration the assertion is about.
     */
    private static final Duration SIGNED_IN_IDLE_LIMIT = Duration.ofMinutes(30);

    /**
     * The same half hour written as a bare number, as {@code SERVER_SERVLET_SESSION_TIMEOUT=1800}
     * would write it; Boot reads a bare session timeout as seconds. Not private, because a nested
     * context's annotation names it.
     */
    static final int BARE_SECONDS_SESSION_TIMEOUT = 1800;

    /** A secret the fake provider was not built with, for the context that must be refused. */
    static final String WRONG_CLIENT_SECRET = "not-the-providers-client-secret";

    /**
     * The fixed WARN line {@code GatewayRelayProblems} writes for a refused refresh. The sweep below
     * requires it, so a capture that holds no gateway line at all cannot pass for a clean one.
     */
    private static final String REFUSED_REFRESH_LINE = "A relay's authorization could not be renewed";

    /** The scheme {@code client_secret_basic} credentials travel under. */
    private static final String BASIC_PREFIX = "Basic ";

    /** Trailing base64 padding, which an unpadded encoding of the same bytes leaves off. */
    private static final Pattern BASE64_PADDING = Pattern.compile("=+$");

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

    /** The running server, whose Tomcat holds the sessions a sign-in creates. */
    @Autowired
    private ServletWebServerApplicationContext context;

    @BeforeAll
    static void startFakes() throws Exception {
        idp = new FakeOpenIdProvider(CLIENT_SECRET);
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
     * request. The relay strips all three of that offer's headers: Spring Cloud Gateway's hop-by-hop
     * filter takes {@code Upgrade} and {@code Connection}, and each route's own filter takes {@code
     * HTTP2-Settings} (#98). The pin keeps the upgrade-offer tests from depending on that, though.
     * Pinned, anything an upgrade offer leaves at a {@link FakeService} can only have been added by
     * the gateway's own outbound client, which is what {@link #assertNoHttp2UpgradeOffer} is about.
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
     * first because a refused verifier ends in the same 302 to {@code ?signin=failed} as any other
     * failed sign-in; checked first, the failure names its cause rather than an unexpected {@code
     * Location}.
     */
    private void signIn(HttpClient client) throws Exception {
        HttpResponse<Void> callback = completeCallback(client);
        assertThat(idp.lastTokenRequestAuthenticatedClient())
                .as("the token request authenticated as the gateway's client, by client_secret_basic")
                .isTrue();
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

    private static HttpCookie cookieNamed(List<HttpCookie> cookies, String name) {
        return cookies.stream()
                .filter(cookie -> cookie.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie in the jar"));
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
    void
            Given_a_browser_signing_in_When_it_completes_the_code_flow_Then_pkce_is_verified_and_it_lands_on_the_public_url()
                    throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
        }
    }

    /**
     * A session starts with a short idle limit, because until the callback it holds only a started
     * sign-in ({@code GatewayApplicationIT} holds that half). A completed sign-in must give it back
     * ADR-030's half hour, or the owner would be signed out after a few idle minutes. Read from
     * the session Tomcat's own manager holds, under the id the rotated cookie names, because that
     * is what expiry acts on.
     */
    @Test
    void Given_a_completed_sign_in_When_its_session_is_read_Then_its_idle_limit_is_the_configured_thirty_minutes()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);

            Session session = webApps().getFirst().getManager().findSession(sessionCookieValue(client));

            assertThat(session).as("the signed-in session Tomcat holds").isNotNull();
            assertThat(session.getMaxInactiveInterval())
                    .as("the signed-in session's idle limit, in seconds")
                    .isEqualTo((int) SIGNED_IN_IDLE_LIMIT.toSeconds());
        }
    }

    /**
     * The short limit is for a session the login entry creates, never for one it finds. A
     * signed-in session that asks for the login entry again keeps ADR-030's half hour, so starting
     * a sign-in again cannot shorten it. Read from Tomcat's own session manager, as above.
     */
    @Test
    void Given_a_signed_in_session_When_it_requests_the_login_entry_again_Then_its_idle_limit_stays_thirty_minutes()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String signedIn = sessionCookieValue(client);

            HttpResponse<Void> entry = getFollowingNothing(client, gatewayUrl("/bff/oauth2/authorization/keycloak"));
            assertThat(entry.statusCode()).isEqualTo(302);

            Session session = webApps().getFirst().getManager().findSession(signedIn);
            assertThat(session).as("the signed-in session Tomcat holds").isNotNull();
            assertThat(session.getMaxInactiveInterval())
                    .as("the signed-in session's idle limit after the login entry, in seconds")
                    .isEqualTo((int) SIGNED_IN_IDLE_LIMIT.toSeconds());
        }
    }

    /** The web applications the running Tomcat holds, as the container itself sees them. */
    private List<Context> webApps() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        return Arrays.stream(server.getTomcat().getHost().findChildren())
                .map(Context.class::cast)
                .toList();
    }

    /**
     * "The browser holds two cookies and no token", after a real sign-in rather than MockMvc's
     * {@code oidcLogin()}, which skips the callback's login filter and success handler and so
     * cannot see a cookie either of them adds. The jar is read whole, because the claim is "these
     * and nothing else", and every value in it is searched for each token the provider issued.
     *
     * <p>The sign-in rotates the session id and so re-issues the session cookie, which is why its
     * flag is read here as well as where a session is first created ({@code GatewayApplicationIT}):
     * the session cookie stays out of script's reach, and the CSRF cookie stays readable, because
     * the web client must copy it into a header.
     */
    @Test
    void
            Given_a_completed_sign_in_When_the_browsers_cookies_are_read_Then_it_holds_only_an_httponly_session_cookie_and_a_readable_csrf_cookie()
                    throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            List<String> tokens = List.of(idp.lastAccessToken(), idp.lastRefreshToken(), idp.lastIdToken());

            List<HttpCookie> cookies = ((CookieManager) client.cookieHandler().orElseThrow())
                    .getCookieStore()
                    .getCookies();

            assertThat(cookies)
                    .extracting(HttpCookie::getName)
                    .containsExactlyInAnyOrder(SESSION_COOKIE, "FINASSIST-XSRF-TOKEN");
            assertThat(cookieNamed(cookies, SESSION_COOKIE).isHttpOnly())
                    .as("the session cookie is HttpOnly")
                    .isTrue();
            assertThat(cookieNamed(cookies, "FINASSIST-XSRF-TOKEN").isHttpOnly())
                    .as("the CSRF cookie is HttpOnly")
                    .isFalse();
            for (HttpCookie cookie : cookies) {
                for (String token : tokens) {
                    assertThat(cookie.getValue())
                            .as("the %s cookie", cookie.getName())
                            .doesNotContain(token);
                }
            }
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
     * would otherwise go unnoticed. {@code HTTP2-Settings} is on it because Spring Cloud Gateway's
     * own hop-by-hop filter leaves it alone (#98); {@link
     * #Given_a_signed_in_session_When_a_relayed_request_carries_HTTP2_Settings_Then_neither_upstream_receives_it}
     * proves it never arrives.
     */
    @Test
    void Given_the_shipped_relay_routes_When_their_filters_are_read_Then_each_strips_the_browsers_own_headers() {
        assertThat(routes.getRoutes()).extracting(RouteProperties::getId).containsExactly("ledger", "assistant");

        for (RouteProperties route : routes.getRoutes()) {
            assertThat(removedHeaders(route))
                    .as("headers the %s route removes", route.getId())
                    .containsExactlyInAnyOrder("Cookie", XSRF_HEADER, "Authorization", "HTTP2-Settings");
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
     * The token is accepted from the renamed header alone (ADR-030), on the write that matters
     * most: one relayed to the owner's books with the owner's token attached.
     *
     * <p>Spring's default handler also reads a {@code _csrf} request parameter, from the query
     * string or a form body, and either can be sent without a header. Each is tried with the
     * cookie's value under an all-zero mask, the form that parameter path would unmask and accept,
     * and each must be refused before the relay — the ledger receives nothing — while the session
     * the request rode on stays signed in.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void
            Given_a_signed_in_session_When_a_relayed_write_carries_the_csrf_token_only_as_a_request_parameter_Then_it_is_refused_403_and_not_relayed(
                    boolean inFormBody) throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String parameter =
                    CSRF_PARAMETER + "=" + URLEncoder.encode(masked(csrfTokenValue(client)), StandardCharsets.UTF_8);
            HttpRequest.Builder request = HttpRequest.newBuilder().timeout(TIMEOUT);
            if (inFormBody) {
                request.uri(URI.create(gatewayUrl(LEDGER_TRANSACTIONS)))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(parameter));
            } else {
                request.uri(URI.create(gatewayUrl(LEDGER_TRANSACTIONS + "?" + parameter)))
                        .POST(HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("CSRF_TOKEN_INVALID");
            assertThat(ledger.lastRequest()).isNull();
            assertThat(relay(client, "/bff/me").statusCode())
                    .as("GET /bff/me after the refused write")
                    .isEqualTo(200);
        }
    }

    /**
     * {@code cookieValue} under an all-zero mask: the mask, then the value itself, base64url-encoded,
     * which is a value Spring's {@code XorCsrfTokenRequestAttributeHandler} unmasks back to the
     * cookie's.
     */
    private static String masked(String cookieValue) {
        byte[] token = cookieValue.getBytes(StandardCharsets.UTF_8);
        byte[] maskThenToken = new byte[token.length * 2];
        System.arraycopy(token, 0, maskThenToken, token.length, token.length);
        return Base64.getUrlEncoder().encodeToString(maskThenToken);
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
     * A client-supplied {@code HTTP2-Settings} header stops at the gateway, on both routes (#98).
     *
     * <p>Spring Cloud Gateway's {@code RemoveHopByHopRequestHeadersFilter} strips an incoming
     * {@code Upgrade} and {@code Connection}, but its removal set omits {@code HTTP2-Settings}, so
     * without a route filter of its own the header reaches the upstream as the browser sent it.
     * Nothing upstream acts on it alone today. The guarantee is that nothing upstream ever gets the
     * chance, so each route is asserted separately and neither can lose its filter unnoticed.
     */
    @Test
    void Given_a_signed_in_session_When_a_relayed_request_carries_HTTP2_Settings_Then_neither_upstream_receives_it()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);

            for (String path :
                    List.of("/ledger/api/v1/accounts", "/assistant/api/v1/portfolio/holdings/ACME/position")) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(gatewayUrl(path)))
                                .timeout(TIMEOUT)
                                .header("HTTP2-Settings", CLIENT_HTTP2_SETTINGS)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(200);
            }

            assertThat(ledger.lastRequest().hasHeader("http2-settings"))
                    .as("HTTP2-Settings at the ledger")
                    .isFalse();
            assertThat(assistant.lastRequest().hasHeader("http2-settings"))
                    .as("HTTP2-Settings at the assistant")
                    .isFalse();
        }
    }

    /**
     * A service's answer headers that would change the browser's state for the gateway's whole
     * origin stop at the gateway, on both routes, and so does its {@code Server}. The browser's
     * cookies, site data, transport policy and CORS grants for this origin are the gateway's to
     * set; {@code Server} names the service's own software, which the browser has no use for.
     *
     * <p>The CSRF cookie is dropped from the jar first, so the gateway writes a fresh one on the
     * relayed answer itself. That one must survive: the removal is of what the service answered,
     * not of every cookie on the response.
     */
    @Test
    void Given_an_upstream_answer_carrying_origin_wide_headers_When_relayed_Then_the_browser_receives_none_of_them()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            ledger.respondNext(200, "application/json", "{}", UPSTREAM_ONLY_HEADERS);
            assistant.respondNext(200, "application/json", "{}", UPSTREAM_ONLY_HEADERS);

            for (String path :
                    List.of("/ledger/api/v1/accounts", "/assistant/api/v1/portfolio/holdings/ACME/position")) {
                CookieStore jar = ((CookieManager) client.cookieHandler().orElseThrow()).getCookieStore();
                jar.remove(null, cookieNamed(jar.getCookies(), CSRF_COOKIE));

                HttpResponse<String> response = relay(client, path);

                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(response.headers().allValues("set-cookie"))
                        .as("the Set-Cookie headers %s answered", path)
                        .noneMatch(header -> header.contains(UPSTREAM_COOKIE_VALUE))
                        .anyMatch(header -> header.startsWith(CSRF_COOKIE + "="));
                for (String name : UPSTREAM_ONLY_HEADERS.keySet()) {
                    if (!name.equals("Set-Cookie")) {
                        assertThat(response.headers().allValues(name))
                                .as("the %s headers %s answered", name, path)
                                .isEmpty();
                    }
                }
            }
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
     * session or redeem anything on the owner's behalf.
     *
     * <p>Two browsers, so that every moment a secret is handled falls inside one capture. The first
     * signs in, makes a relay that must refresh its expired access token first - a successful
     * refresh and a successful relay, so the second generation of tokens is in play too - and signs
     * out, which hands the ID token back as {@code id_token_hint}. The second signs in and makes a
     * relay whose refresh the provider refuses, the highest-risk moment in the whole relay.
     *
     * <p>Every secret value those flows handle is searched for: both generations of tokens, the
     * authorization {@code code} and {@code state} each browser carried to the callback, each PKCE
     * verifier, the client's {@code Basic} credentials as they left for the token endpoint, the
     * client secret, each CSRF token, and each session id before and after its sign-in. The code
     * and the state are searched in every form a log line could carry them: decoded, URL-encoded
     * as a logged query holds them, and without base64 padding, because Spring's {@code state} is
     * padded base64url whose {@code =} a query carries as {@code %3D}. The refused refresh's own
     * WARN line must be there too: without it, a capture that saw none of the gateway's output
     * would pass this test as easily as a clean log.
     */
    @Test
    void
            Given_a_sign_in_a_refresh_a_sign_out_and_a_refused_refresh_When_the_logs_are_swept_Then_no_secret_appears_in_any_form(
                    CapturedOutput output) throws Exception {
        List<String> secrets = new ArrayList<>(List.of(CLIENT_SECRET));
        idp.setAccessTokenExpiresInSeconds(-120);
        try (HttpClient browser = newClient()) {
            secrets.addAll(signInRecordingSecrets(browser));
            String idToken = idp.lastIdToken();
            String signInAccessToken = idp.lastAccessToken();
            idp.setAccessTokenExpiresInSeconds(300);

            HttpResponse<String> relayed = relay(browser, "/ledger/api/v1/accounts");
            assertThat(relayed.statusCode()).isEqualTo(200);
            assertThat(idp.lastAccessToken())
                    .as("the access token after the refresh")
                    .isNotEqualTo(signInAccessToken);
            assertThat(ledger.lastRequest().header("authorization"))
                    .as("the relay carried the refreshed access token")
                    .isEqualTo("Bearer " + idp.lastAccessToken());
            secrets.addAll(List.of(idp.lastAccessToken(), idp.lastRefreshToken()));

            HttpResponse<String> signedOut = relayPost(browser, "/bff/logout", csrfTokenValue(browser));
            assertThat(signedOut.statusCode()).isEqualTo(200);
            assertThat(signedOut.body())
                    .as("the sign-out handed the ID token back as id_token_hint")
                    .contains(idToken);
        }
        idp.setAccessTokenExpiresInSeconds(-120);
        try (HttpClient browser = newClient()) {
            secrets.addAll(signInRecordingSecrets(browser));
            idp.refuseNextRefresh();

            HttpResponse<String> refused = relay(browser, "/ledger/api/v1/accounts");
            assertThat(refused.statusCode()).isEqualTo(401);
        }

        String logs = output.getAll();
        assertThat(logs).as("the capture holds the gateway's own output").contains(REFUSED_REFRESH_LINE);
        assertThat(secrets)
                .as("values the sweep searches for")
                .allSatisfy(value -> assertThat(value).isNotBlank());
        assertThat(secrets.stream().filter(logs::contains).toList())
                .as("secret values found in the captured logs")
                .isEmpty();
    }

    /**
     * A successful sign-in, returning every secret value it put in play: the code and state in
     * each {@link #loggableForms} form, the PKCE verifier, the three tokens issued, the client's
     * {@code Basic} credentials, the CSRF token, and the session id before and after.
     */
    private List<String> signInRecordingSecrets(HttpClient client) throws Exception {
        URI authorizeUri = startLogin(client);
        String preSignInSession = sessionCookieValue(client);
        URI authorizationResponse = authorizationResponse(client, authorizeUri);
        String code = queryParam(authorizationResponse, "code");
        String state = queryParam(authorizationResponse, "state");

        // The provider's query exactly as it encoded it, as a browser would carry it, so the padded
        // state reaches the gateway as %3D rather than re-encoded by sendCallback.
        HttpResponse<Void> callback = getFollowingNothing(
                client, gatewayUrl(authorizationResponse.getRawPath() + "?" + authorizationResponse.getRawQuery()));

        assertThat(callback.headers().firstValue("Location")).hasValue(WEB_ORIGIN + "/");
        String clientAuthorization = idp.lastClientAuthorization();
        assertThat(clientAuthorization).as("the token request's Authorization").startsWith(BASIC_PREFIX);
        List<String> secrets = new ArrayList<>();
        secrets.addAll(loggableForms(code));
        secrets.addAll(loggableForms(state));
        secrets.addAll(List.of(
                idp.lastCodeVerifier(),
                idp.lastAccessToken(),
                idp.lastRefreshToken(),
                idp.lastIdToken(),
                clientAuthorization.substring(BASIC_PREFIX.length()),
                csrfTokenValue(client),
                preSignInSession,
                sessionCookieValue(client)));
        return secrets;
    }

    /** {@code value} decoded, URL-encoded, and with any trailing base64 padding removed. */
    private static List<String> loggableForms(String value) {
        return List.of(
                value,
                URLEncoder.encode(value, StandardCharsets.UTF_8),
                BASE64_PADDING.matcher(value).replaceAll(""));
    }

    /**
     * A gateway configured with a client secret the provider does not hold: the provider refuses
     * its code exchange {@code 401 invalid_client}, and the sign-in fails rather than completing.
     * The fake's client check is otherwise never refused, so without this a fake that waved every
     * client through would leave each successful sign-in in this class proving nothing about it.
     */
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "finassist.oidc.client-secret=" + WRONG_CLIENT_SECRET,
                "finassist.web.public-url=http://localhost:5173",
                "finassist.http.allowed-hosts=localhost,127.0.0.1",
                "finassist.http.cookie-secure=false"
            })
    class WrongClientSecret {

        @LocalServerPort
        private int wrongSecretPort;

        @DynamicPropertySource
        static void oidcProperties(DynamicPropertyRegistry registry) {
            registry.add("finassist.oidc.public-url", idp::baseUrl);
            registry.add("finassist.oidc.internal-url", idp::baseUrl);
            registry.add("finassist.relay.ledger-uri", ledger::baseUrl);
            registry.add("finassist.relay.assistant-uri", assistant::baseUrl);
        }

        @Test
        void Given_a_gateway_holding_the_wrong_client_secret_When_it_exchanges_the_code_Then_the_sign_in_fails()
                throws Exception {
            try (HttpClient client = newClient()) {
                URI authorizeUri = URI.create(getFollowingNothing(
                                client, "http://localhost:" + wrongSecretPort + "/bff/oauth2/authorization/keycloak")
                        .headers()
                        .firstValue("Location")
                        .orElseThrow());
                URI codeRedirect = URI.create(getFollowingNothing(client, authorizeUri.toString())
                        .headers()
                        .firstValue("Location")
                        .orElseThrow());

                HttpResponse<Void> callback = getFollowingNothing(
                        client,
                        new URI(
                                        "http",
                                        null,
                                        "localhost",
                                        wrongSecretPort,
                                        codeRedirect.getPath(),
                                        codeRedirect.getQuery(),
                                        null)
                                .toString());

                assertThat(idp.lastClientAuthorization())
                        .as("the code exchange reached the provider, with Basic credentials")
                        .startsWith(BASIC_PREFIX);
                assertThat(idp.lastTokenRequestAuthenticatedClient())
                        .as("the provider authenticated the gateway's client")
                        .isFalse();
                assertSignInFailed(callback);
            }
        }
    }

    /**
     * {@code server.servlet.session.timeout} written as a bare number, which Boot reads as seconds.
     * A completed sign-in must give the session back the same limit every new session gets, here
     * {@value GatewayLoginFlowIT#BARE_SECONDS_SESSION_TIMEOUT} seconds, however the property is
     * written: the success handler must not read the property its own way, in a unit of its own.
     */
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "finassist.oidc.client-secret=" + CLIENT_SECRET,
                "finassist.web.public-url=http://localhost:5173",
                "finassist.http.allowed-hosts=localhost,127.0.0.1",
                "finassist.http.cookie-secure=false",
                "server.servlet.session.timeout=" + BARE_SECONDS_SESSION_TIMEOUT
            })
    class BareSecondsSessionTimeout {

        @LocalServerPort
        private int bareSecondsPort;

        @Autowired
        private ServletWebServerApplicationContext bareSecondsContext;

        @DynamicPropertySource
        static void oidcProperties(DynamicPropertyRegistry registry) {
            registry.add("finassist.oidc.public-url", idp::baseUrl);
            registry.add("finassist.oidc.internal-url", idp::baseUrl);
            registry.add("finassist.relay.ledger-uri", ledger::baseUrl);
            registry.add("finassist.relay.assistant-uri", assistant::baseUrl);
        }

        @Test
        void Given_a_session_timeout_in_bare_seconds_When_a_sign_in_completes_Then_its_idle_limit_is_that_many_seconds()
                throws Exception {
            try (HttpClient client = newClient()) {
                URI authorizeUri = URI.create(getFollowingNothing(
                                client, "http://localhost:" + bareSecondsPort + "/bff/oauth2/authorization/keycloak")
                        .headers()
                        .firstValue("Location")
                        .orElseThrow());
                URI codeRedirect = URI.create(getFollowingNothing(client, authorizeUri.toString())
                        .headers()
                        .firstValue("Location")
                        .orElseThrow());
                HttpResponse<Void> callback = getFollowingNothing(
                        client,
                        new URI(
                                        "http",
                                        null,
                                        "localhost",
                                        bareSecondsPort,
                                        codeRedirect.getPath(),
                                        codeRedirect.getQuery(),
                                        null)
                                .toString());
                assertThat(callback.headers().firstValue("Location")).hasValue(WEB_ORIGIN + "/");

                TomcatWebServer server = (TomcatWebServer) bareSecondsContext.getWebServer();
                Context web = (Context) server.getTomcat().getHost().findChildren()[0];
                Session session = web.getManager().findSession(sessionCookieValue(client));

                assertThat(session).as("the signed-in session Tomcat holds").isNotNull();
                assertThat(session.getMaxInactiveInterval())
                        .as("the signed-in session's idle limit, in seconds")
                        .isEqualTo(BARE_SECONDS_SESSION_TIMEOUT);
            }
        }
    }

    /**
     * A dedicated context whose ledger and assistant URIs both point at ports nothing listens on,
     * leaving the outer class's live {@code ledger} and {@code assistant} fakes untouched for every
     * other test.
     */
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "finassist.oidc.client-secret=" + CLIENT_SECRET,
                "finassist.web.public-url=http://localhost:5173",
                "finassist.http.allowed-hosts=localhost,127.0.0.1",
                "finassist.http.cookie-secure=false"
            })
    class UpstreamsDown {

        /** The address {@code server.address} defaults to in {@code application.yml}. */
        private static final String LOOPBACK = "127.0.0.1";

        @LocalServerPort
        private int downPort;

        /**
         * The dead upstreams' ports, each held by a socket that is bound but never listens. Bound
         * to the address the gateway binds, each keeps that address and the wildcard on its port
         * from any other socket, so nothing can listen where the relay connects. Never listening, a
         * connection to one is refused rather than left to time out: at once on Linux, and after
         * about two seconds of SYN retries on Windows, well inside the relay's five-second connect
         * timeout.
         *
         * <p>Reserving a port and releasing it is not enough. The gateway this context starts
         * afterwards binds port 0 too, and can be handed the released port. The relay then reaches
         * the gateway itself, which answers the stripped, cookie-less request with 401 instead of
         * a 502. That reproduces exactly the failure #238 recorded on CI.
         */
        private static Socket deadLedgerSocket;

        private static Socket deadAssistantSocket;

        @DynamicPropertySource
        static void deadUpstreams(DynamicPropertyRegistry registry) throws IOException {
            // Spring calls this on every load of this context, so a reload must not leak the
            // sockets the previous load held.
            releaseDeadUpstreams();
            deadLedgerSocket = heldButNeverListening();
            deadAssistantSocket = heldButNeverListening();
            int deadLedgerPort = deadLedgerSocket.getLocalPort();
            int deadAssistantPort = deadAssistantSocket.getLocalPort();
            registry.add("finassist.oidc.public-url", idp::baseUrl);
            registry.add("finassist.oidc.internal-url", idp::baseUrl);
            // The held address itself, not localhost, which may resolve to an address the held
            // socket does not cover.
            registry.add("finassist.relay.ledger-uri", () -> "http://" + LOOPBACK + ":" + deadLedgerPort);
            registry.add("finassist.relay.assistant-uri", () -> "http://" + LOOPBACK + ":" + deadAssistantPort);
        }

        private static Socket heldButNeverListening() throws IOException {
            Socket held = new Socket();
            // The default, made explicit because the guarantee rests on it. On Linux two sockets
            // share a port only when both set SO_REUSEADDR, and Tomcat's does; Windows' JDK binds
            // exclusively whatever this says.
            held.setReuseAddress(false);
            // The address the gateway binds (server.address), so the two contend for one port
            // rather than coexisting on different addresses.
            held.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0));
            return held;
        }

        @AfterAll
        static void releaseDeadUpstreams() throws IOException {
            for (Socket held : new Socket[] {deadLedgerSocket, deadAssistantSocket}) {
                if (held != null) {
                    held.close();
                }
            }
        }

        private String url(String path) {
            return "http://localhost:" + downPort + path;
        }

        /**
         * What makes the relay tests deterministic: no server this test process starts, the
         * gateway's own Tomcat included, can bind the address and port a relay connects to.
         */
        @Test
        void Given_the_dead_upstreams_ports_When_a_server_tries_to_bind_them_Then_the_bind_is_refused() {
            for (Socket held : List.of(deadLedgerSocket, deadAssistantSocket)) {
                int deadPort = held.getLocalPort();
                InetAddress address = held.getLocalAddress();

                assertThatThrownBy(() -> new ServerSocket(deadPort, 1, address).close())
                        .isInstanceOf(BindException.class);
            }
        }

        @Test
        void Given_the_ledger_is_unreachable_When_relayed_Then_it_answers_502_upstream_unavailable() throws Exception {
            assertRelayAnswers502(url("/ledger/api/v1/accounts"));
        }

        @Test
        void Given_the_assistant_is_unreachable_When_relayed_Then_it_answers_502_upstream_unavailable()
                throws Exception {
            assertRelayAnswers502(url("/assistant/api/v1/portfolio/holdings/ACME/position"));
        }

        /** Signs in against this context's gateway, then asserts the relay's problem for {@code target}. */
        private void assertRelayAnswers502(String target) throws Exception {
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
                // A failed sign-in also ends in a 302, to ?signin=failed, and leaves the relay
                // below to answer 401 for want of a session. Asserted here, that failure is named
                // where it happens rather than passing for the relay's own answer.
                assertThat(callback.headers().firstValue("Location")).contains(WEB_ORIGIN + "/");

                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(target))
                                .timeout(TIMEOUT)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode())
                        .as("the relay's answer, whose body was %s", response.body())
                        .isEqualTo(502);
                assertThat(response.headers().firstValue("content-type")).contains("application/problem+json");
                assertThat(response.body()).contains("\"status\":502").contains("\"code\":\"UPSTREAM_UNAVAILABLE\"");
            }
        }
    }
}
