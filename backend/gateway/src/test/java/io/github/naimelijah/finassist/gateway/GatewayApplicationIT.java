package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.gateway.composition.HostAllowListFilter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/**
 * The gateway as a running server: it boots, answers its own health probe, refuses a foreign
 * {@code Host} header, and runs that refusal ahead of every other filter in the chain the server
 * actually built — the three claims {@code LedgerApplicationIT} proves for the ledger, using the
 * same techniques.
 *
 * <p>It also reads the session cookie a real Tomcat writes. That cannot be done anywhere else:
 * {@code SameSite} never reaches {@code SessionCookieConfig}, because Boot turns
 * {@code server.servlet.session.cookie.same-site} into a {@code CookieSameSiteSupplier} that
 * Tomcat's cookie processor applies as the header is written, so only a real {@code Set-Cookie}
 * proves it. {@code GatewaySecurityTest} covers the chain's own rules with MockMvc, which never
 * builds one. Nothing here exercises a relay route; {@code GatewayLoginFlowIT} does, against fake
 * downstream services.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than {@code TestRestTemplate}, which Spring Boot 4
 * moved out of {@code spring-boot-starter-test} into a separate artefact the ledger also declined
 * to add for the same reason.
 *
 * <p>Every property an assertion here reads a literal for is pinned below, because {@code
 * application.yml} resolves each from a {@code GATEWAY_*} variable of the process environment, and a
 * developer's shell exporting one would otherwise turn this red, or green, for a reason that is not
 * the code's.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finassist.oidc.client-secret=a-test-client-secret",
            "finassist.http.allowed-hosts=localhost,127.0.0.1",
            "finassist.http.cookie-secure=false"
        })
class GatewayApplicationIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** The name Spring Boot registers the security chain under, and the one Tomcat knows it by. */
    private static final String SECURITY_FILTER_NAME = "springSecurityFilterChain";

    private static final String SESSION_COOKIE = "FINASSIST_SESSION";

    private static final String CSRF_COOKIE = "FINASSIST-XSRF-TOKEN";

    /** Creates a session without a sign-in: Spring saves the authorization request in one. */
    private static final String LOGIN_ENTRY = "/bff/oauth2/authorization/keycloak";

    /** The same entry naming a registration that does not exist, which only this gateway can answer. */
    private static final String UNKNOWN_LOGIN_ENTRY = "/bff/oauth2/authorization/not-a-registered-provider";

    /**
     * The shipped outbound budget, {@code spring.http.clients.*} in {@code application.yml}.
     * Spelled here rather than read from the same file the assertion is about: a test that took
     * its expectation from its subject would pass through any change to it.
     */
    private static final Duration SHIPPED_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration SHIPPED_READ_TIMEOUT = Duration.ofSeconds(10);

    /** ADR-030's idle timeout, {@code server.servlet.session.timeout}, spelled here for the same reason. */
    private static final Duration SHIPPED_SESSION_TIMEOUT = Duration.ofMinutes(30);

    /**
     * How long a session holding only a started sign-in may sit idle, spelled here for the same
     * reason: long enough for a first sign-in at Keycloak, half a signed-in session's.
     */
    private static final Duration PRE_SIGN_IN_IDLE_LIMIT = Duration.ofMinutes(15);

    /** The Fetch Metadata header a browser sends to say what kind of request it is making. */
    private static final String SEC_FETCH_MODE = "Sec-Fetch-Mode";

    /** The {@link #SEC_FETCH_MODE} of a navigation, the only mode that may start a sign-in. */
    private static final String NAVIGATE = "navigate";

    /** The Fetch Metadata header naming the requesting page's relation to this origin. */
    private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";

    private static final String CROSS_SITE = "cross-site";

    /** The Fetch Metadata header a browser sends to say where the answer will be used. */
    private static final String SEC_FETCH_DEST = "Sec-Fetch-Dest";

    /** The {@link #SEC_FETCH_DEST} of the browser's own window, the only one a sign-in may open in. */
    private static final String DOCUMENT = "document";

    /** The one Actuator endpoint ADR-030 lets the gateway serve over HTTP, spelled here for the same reason. */
    private static final String HEALTH_ENDPOINT = "health";

    /**
     * What every name that binds to {@code management.endpoints.web.exposure.*} starts with once
     * normalised as {@link #relaxed} does, the canonical {@code MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_}
     * variable form.
     */
    private static final String EXPOSURE_PREFIX = "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_";

    /** The inline-JSON source, {@code spring.application.json}, normalised as {@link #relaxed} does. */
    private static final String INLINE_JSON = "SPRING_APPLICATION_JSON";

    /** The prefix of every {@code spring.config.*} name, which can load another document. */
    private static final String CONFIG_PREFIX = "SPRING_CONFIG_";

    @LocalServerPort
    private int port;

    @Autowired
    private ServletWebServerApplicationContext context;

    /**
     * What {@code spring.http.clients.*} resolved to. {@code HttpClientAutoConfiguration}
     * publishes this bean from {@code HttpClientsProperties}, and {@code
     * ImperativeHttpClientAutoConfiguration} builds the {@code ClientHttpRequestFactory} every
     * outbound call goes through from it — so this is the settings object the application actually
     * uses, not a re-read of the file.
     */
    @Autowired
    private HttpClientSettings outboundHttpSettings;

    /**
     * Every Actuator endpoint this context maps onto the web, from every web supplier: {@code
     * @Endpoint} and {@code @WebEndpoint} beans, and controller and servlet endpoints too, each
     * after the include and exclude filters {@code management.endpoints.web.exposure} configures.
     * So these are the endpoints that list let through, not the ones that merely exist. The
     * ledger's pin reads the same bean, which keeps the two services symmetric.
     */
    @Autowired
    private PathMappedEndpoints webEndpoints;

    /**
     * One client for the class, closed afterwards. {@link HttpClient} owns a selector thread and
     * an executor and is {@link AutoCloseable} on Java 21, so building one per request would leave
     * it running until the JVM exited.
     */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    /**
     * A {@code GET} of {@code path}, carrying any headers given as name, value, name, value.
     */
    private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The status line of a {@code GET} naming a {@code Host} of the caller's choosing.
     *
     * <p>Written by hand over a socket because {@link HttpClient} treats {@code Host} as a restricted
     * header and derives it from the URI, which is exactly what a rebound page's browser does not do.
     */
    private String statusLineFor(String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }

    /**
     * The one {@code Set-Cookie} header naming the given cookie, as the server actually wrote it.
     * Read raw rather than through a cookie parser, because the attributes are the assertion.
     */
    private static String setCookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Set-Cookie header named " + name));
    }

    @Test
    void Given_the_gateway_When_it_boots_Then_health_reports_up() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /**
     * The probe Compose's healthcheck reads, since every healthcheck probes readiness (#142).
     * Health above can answer while this one is missing: the readiness group exists
     * only while {@code management.endpoint.health.probes.enabled} is on.
     */
    @Test
    void Given_the_gateway_When_it_boots_Then_readiness_reports_up() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /**
     * The budget that bounds every call this gateway makes to Keycloak — the authorization-code
     * exchange, the {@code refresh_token} grant and the JWK-set fetch — and every relayed request
     * besides.
     *
     * <p>{@code GatewayKeycloakTimeoutIT} proves those three calls are bounded, but it supplies
     * its own one-second budget to do it, so it would stay green with the shipped values deleted.
     * {@code HttpClientSettings.defaults()} leaves both durations {@code null} and the JDK client
     * has no read timeout of its own, so deleting those two lines does not restore a default — it
     * returns every outbound call to unbounded, which is the defect commit 6217273 fixed. Nothing
     * else in this module reads them: this context boots the shipped {@code application.yml} with
     * no override, which is the whole point of asserting it here.
     *
     * <p>Changing the budget means changing this assertion, which is deliberate: the numbers are
     * written down twice already — in {@code application.yml} and in the README's note on the
     * gateway's outbound timeouts — and a value only one place knows about is how this budget
     * came to be missing in the first place.
     */
    @Test
    void Given_the_shipped_configuration_When_the_outbound_http_settings_are_read_Then_they_carry_the_agreed_budget() {
        assertThat(outboundHttpSettings.connectTimeout()).isEqualTo(SHIPPED_CONNECT_TIMEOUT);
        assertThat(outboundHttpSettings.readTimeout()).isEqualTo(SHIPPED_READ_TIMEOUT);
    }

    /**
     * ADR-030's exposure rule: health alone, because this process holds the client secret and every
     * signed-in session's tokens, and {@code heapdump} or {@code env} would hand over either.
     *
     * <p>{@code GatewaySecurityTest} shows the chain refusing two such endpoints, but only the
     * chain: with {@code /actuator/**} permitted there and {@code *} exposed here, both layers would
     * have to fail at once for anything to notice. This pins the exposure layer on its own, so a
     * widened list fails the build whatever the chain says.
     *
     * <p>The process must not set the list itself first, nor be able to. A {@code
     * MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_*} variable sets it; {@code SPRING_APPLICATION_JSON} carries
     * inline properties, and a {@code SPRING_CONFIG_*} variable ({@code _LOCATION}, {@code
     * _ADDITIONAL_LOCATION}, {@code _IMPORT}) points at another document. Each outranks {@code
     * application.yml}, so one could turn this red for a reason that is not the code's, or green
     * with the shipped file widened beneath it. The two that only can are refused whatever they hold,
     * since parsing them is what Spring is for. Names are compared as Spring's relaxed binding reads
     * them, case-insensitive with {@code .} and {@code -} as {@code _}, so the matching system
     * properties are refused too. Not refused: an active profile, and an {@code application.yml} in
     * the working directory or its {@code config/}, which reach the list only through a document this
     * check does not look for; the gateway ships no profile file.
     */
    @Test
    void Given_the_shipped_configuration_When_the_web_exposed_endpoints_are_resolved_Then_health_is_the_only_one() {
        assertThat(exposureOverrides())
                .as("environment variables and system properties that would, or could, decide the exposure in place of"
                        + " application.yml; unset them, or this pin reads the environment, not the shipped file")
                .isEmpty();

        assertThat(webEndpoints.stream().map(GatewayApplicationIT::idOf).toList())
                .as("the Actuator endpoints exposed over HTTP")
                .containsExactly(HEALTH_ENDPOINT);
    }

    /** The name of every variable or system property in this process that overrides, or can override, the list. */
    private static List<String> exposureOverrides() {
        Stream<String> variables = System.getenv().keySet().stream()
                .filter(name -> canSetTheExposure(relaxed(name)))
                .map(name -> "environment variable " + name);
        Stream<String> properties = System.getProperties().stringPropertyNames().stream()
                .filter(name -> canSetTheExposure(relaxed(name)))
                .map(name -> "system property " + name);
        return Stream.concat(variables, properties).sorted().toList();
    }

    private static boolean canSetTheExposure(String relaxedName) {
        return relaxedName.startsWith(EXPOSURE_PREFIX)
                || relaxedName.equals(INLINE_JSON)
                || relaxedName.startsWith(CONFIG_PREFIX);
    }

    private static String relaxed(String name) {
        return name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static String idOf(PathMappedEndpoint endpoint) {
        if (endpoint instanceof ExposableEndpoint<?> exposable) {
            return exposable.getEndpointId().toLowerCaseString();
        }
        throw new AssertionError("Actuator mapped an endpoint that carries no id, at " + endpoint.getRootPath());
    }

    /**
     * DNS rebinding as the gateway sees it: a request that arrives on its loopback port while
     * naming another host (ADR-030). Actuator is the target because it is served whatever else
     * exists, and the allow-list covers it too.
     */
    @Test
    void Given_a_request_naming_a_foreign_host_When_it_reaches_the_gateway_Then_it_is_refused_with_403()
            throws Exception {
        assertThat(statusLineFor("/actuator/health", "evil.example")).startsWith("HTTP/1.1 403");
    }

    /**
     * The Host allow-list must see a request before any other filter does (ADR-030), and its
     * registration alone does not guarantee that. Boot adapts several of its own filters -
     * {@code OrderedCharacterEncodingFilter} among them - into the servlet context straight from a
     * plain {@code Filter} bean, never through an {@code AbstractFilterRegistrationBean}, so their
     * position is decided by Boot's own registration order and not by a number this project sets.
     * That one in fact runs second, immediately behind this filter. Spring Security's is a
     * {@code DelegatingFilterProxyRegistrationBean} that Boot orders at {@code -100}, which is a
     * number a property can change. So this reads the chain the running server actually built,
     * exactly as the ledger's equivalent test does, rather than the numbers it was asked to build
     * it from.
     *
     * <p>Security running after the allow-list is what makes the allow-list a defence rather than
     * a formality: a request under a foreign {@code Host} is refused before any authentication,
     * session lookup or CSRF check has touched it.
     */
    @Test
    void Given_the_running_gateway_When_its_filter_chain_is_read_Then_the_host_allow_list_runs_before_security() {
        List<Context> webApps = webApps();
        List<String> chain = webApps.stream()
                .flatMap(web -> Arrays.stream(web.findFilterMaps())
                        .map(map -> web.findFilterDef(map.getFilterName()).getFilterClass()))
                .toList();
        // The same maps in the same order, by the name the container knows each filter as. Boot
        // registers the security chain as an anonymous subclass of
        // DelegatingFilterProxyRegistrationBean, so its class name identifies the registration
        // mechanism and not the filter; its name is exactly springSecurityFilterChain.
        List<String> names = webApps.stream()
                .flatMap(web -> Arrays.stream(web.findFilterMaps()).map(FilterMap::getFilterName))
                .toList();

        assertThat(chain).first().isEqualTo(HostAllowListFilter.class.getName());
        // Everything after position 0, so "present here" is already "after the allow-list".
        assertThat(names.subList(1, names.size()))
                .withFailMessage("springSecurityFilterChain must run, and must run after the Host allow-list")
                .contains(SECURITY_FILTER_NAME);
    }

    /** The web applications the running Tomcat holds, as the container itself sees them. */
    private List<Context> webApps() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        return Arrays.stream(server.getTomcat().getHost().findChildren())
                .map(Context.class::cast)
                .toList();
    }

    /**
     * ADR-030's idle timeout as the running server applies it: half an hour without a request ends
     * the session, and with it the owner's tokens, which live nowhere else. Read from Tomcat's own
     * context, in the whole minutes Boot converts {@code server.servlet.session.timeout} into,
     * because that is the value sessions are actually created with, not the property it came from.
     */
    @Test
    void Given_the_shipped_configuration_When_the_servers_session_timeout_is_read_Then_it_is_thirty_minutes() {
        assertThat(webApps())
                .extracting(Context::getSessionTimeout)
                .as("each web application's session timeout, in minutes")
                .containsExactly((int) SHIPPED_SESSION_TIMEOUT.toMinutes());
    }

    /**
     * The one cookie the browser holds that is worth stealing, as Tomcat actually writes it. The
     * login entry is the request that creates a session, because Spring saves the authorization
     * request in it before redirecting to Keycloak - so this needs no sign-in and no Keycloak.
     *
     * <p>{@code HttpOnly} keeps it out of reach of any script on the page, {@code SameSite=Lax}
     * keeps a cross-site page from sending it on anything but a top-level navigation, and
     * {@code Secure} is absent because development is plain HTTP and {@code GATEWAY_COOKIE_SECURE}
     * defaults to false; behind TLS it must be set (ADR-030).
     */
    @Test
    void Given_a_request_that_starts_a_session_When_the_cookie_is_written_Then_it_is_httponly_and_same_site_lax()
            throws Exception {
        HttpResponse<String> response = get(LOGIN_ENTRY);

        assertThat(setCookie(response, SESSION_COOKIE))
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure");
    }

    /**
     * A sign-in starts only from a top-level navigation, which is how the web client reaches the
     * login entry: a link the browser follows in its own window, marked {@code Sec-Fetch-Mode:
     * navigate} and {@code Sec-Fetch-Dest: document}. A request a page makes for an image, a script
     * or a {@code fetch} names another mode, and a navigation into a frame names another
     * destination. The login entry creates a session for each request it accepts, so each of these
     * is refused before any session exists, 403 {@code FORBIDDEN} rather than 401: the endpoint is
     * public, so no credential would change the answer. A client that sends neither header, as
     * every other test here does, is not refused: only a browser sends them.
     */
    @ParameterizedTest
    @CsvSource({"no-cors, image", "cors, empty", "same-origin, empty", "navigate, iframe", "navigate, embed"})
    void
            Given_a_browser_request_that_is_not_a_top_level_navigation_When_it_reaches_the_login_entry_Then_it_is_refused_403_and_no_session_is_created(
                    String fetchMode, String fetchDest) throws Exception {
        HttpResponse<String> response =
                get(LOGIN_ENTRY, SEC_FETCH_MODE, fetchMode, SEC_FETCH_DEST, fetchDest, SEC_FETCH_SITE, CROSS_SITE);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("content-type")).contains("application/problem+json");
        assertThat(response.body())
                .contains("\"code\":\"FORBIDDEN\"")
                .contains("A sign-in starts only from a top-level navigation.")
                .doesNotContain("session");
        assertThat(response.headers().firstValue("location")).isEmpty();
        assertThat(response.headers().allValues("set-cookie"))
                .as("the cookies the refusal set")
                .noneMatch(header -> header.startsWith(SESSION_COOKIE + "="));
    }

    /** The navigation the web client makes is still sent on to Keycloak. */
    @Test
    void Given_a_top_level_navigation_When_it_reaches_the_login_entry_Then_it_is_redirected_to_keycloak()
            throws Exception {
        HttpResponse<String> response =
                get(LOGIN_ENTRY, SEC_FETCH_MODE, NAVIGATE, SEC_FETCH_DEST, DOCUMENT, SEC_FETCH_SITE, "same-origin");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(setCookie(response, SESSION_COOKIE)).isNotBlank();
    }

    /**
     * A session the login entry creates holds nothing but the authorization request, and it lives
     * only as long as a sign-in needs, rather than ADR-030's half hour: a sign-in started and never
     * finished does not hold memory for thirty minutes. Read from the session Tomcat's own manager
     * holds, under the id the cookie names, because that is what expiry acts on. A completed
     * sign-in restores the half hour, which {@code GatewayLoginFlowIT} holds.
     */
    @Test
    void
            Given_a_sign_in_started_and_never_finished_When_its_session_is_read_Then_its_idle_limit_is_the_pre_sign_in_limit()
                    throws Exception {
        HttpResponse<String> response = get(LOGIN_ENTRY);
        assertThat(response.statusCode()).isEqualTo(302);

        Session session = sessionNamedBy(response);

        assertThat(session.getMaxInactiveInterval())
                .as("the started sign-in's idle limit, in seconds")
                .isEqualTo((int) PRE_SIGN_IN_IDLE_LIMIT.toSeconds());
    }

    /** The session Tomcat holds under the id the response's session cookie names. */
    private Session sessionNamedBy(HttpResponse<String> response) throws IOException {
        String cookie = setCookie(response, SESSION_COOKIE);
        String id = cookie.substring(SESSION_COOKIE.length() + 1, cookie.indexOf(';'));
        Session session = webApps().getFirst().getManager().findSession(id);
        assertThat(session).as("the session Tomcat holds under the cookie's id").isNotNull();
        return session;
    }

    /**
     * The half of {@code LoginEntryFailureHandler}'s guarantee that only a real container shows.
     *
     * <p>Left at Spring's default, the redirect filter answered this with {@code
     * sendError(500)}; the container then error-dispatched to {@code /error}, which the chain
     * permits by dispatcher type, and Boot's {@code BasicErrorController} rendered its own body —
     * a {@code timestamp}, an {@code error} and the caller's {@code path}, with no {@code code}
     * member and not {@code application/problem+json}. MockMvc performs no error dispatch, so
     * {@code GatewaySecurityTest} cannot see that body at all; this does.
     */
    @Test
    void Given_a_login_entry_naming_an_unknown_provider_When_requested_Then_it_answers_a_problem_not_boots_error_page()
            throws Exception {
        HttpResponse<String> response = get(UNKNOWN_LOGIN_ENTRY);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("content-type")).contains("application/problem+json");
        assertThat(response.body())
                .contains("\"code\":\"UNAUTHENTICATED\"")
                .doesNotContain("Internal Server Error")
                .doesNotContain("\"timestamp\"")
                .doesNotContain("\"path\"");
    }

    /**
     * The other cookie the browser holds, written by {@code CookieCsrfTokenRepository}'s customiser
     * rather than by Tomcat, and therefore able to disagree with the session cookie above.
     *
     * <p>{@code SameSite=Lax} is defence in depth here, not the CSRF defence itself — that is the
     * header comparison, which a cross-site page cannot satisfy because it cannot read this
     * cookie. It is asserted because the attribute is set on purpose rather than left to Spring's
     * default of "whatever the current request's scheme is", and nothing else would notice it
     * being dropped. {@code HttpOnly} must be absent, for the opposite reason to the session
     * cookie's: the web client's own script has to read this one.
     */
    @Test
    void Given_a_request_that_starts_a_session_When_the_csrf_cookie_is_written_Then_it_is_same_site_lax_and_readable()
            throws Exception {
        HttpResponse<String> response = get(LOGIN_ENTRY);

        assertThat(setCookie(response, CSRF_COOKIE))
                .contains("SameSite=Lax")
                .doesNotContain("HttpOnly")
                .doesNotContain("Secure");
    }

    /**
     * The direction of {@code GATEWAY_COOKIE_SECURE} that matters in production. The default of
     * {@code false} is asserted above by the absence of {@code Secure}, and an absence passes just
     * as well when a property is ignored altogether — so the setting is only shown to be honoured
     * by turning it on and watching the attribute appear.
     *
     * <p>Both cookies, because they are set by different machinery that could disagree: the session
     * cookie by Tomcat from {@code server.servlet.session.cookie.secure}, and the CSRF cookie by
     * {@code CookieCsrfTokenRepository}'s customiser in {@code GatewaySecurityConfiguration}. Both
     * read {@code finassist.http.cookie-secure}, which is the point of there being one property.
     */
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "finassist.oidc.client-secret=a-test-client-secret",
                "finassist.http.allowed-hosts=localhost,127.0.0.1",
                "finassist.http.cookie-secure=true"
            })
    class BehindTls {

        @LocalServerPort
        private int tlsPort;

        @Test
        void Given_cookie_secure_is_on_When_the_cookies_are_written_Then_both_carry_the_secure_attribute()
                throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + tlsPort + LOGIN_ENTRY))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(setCookie(response, SESSION_COOKIE)).contains("Secure");
            assertThat(setCookie(response, CSRF_COOKIE)).contains("Secure");
        }
    }
}
