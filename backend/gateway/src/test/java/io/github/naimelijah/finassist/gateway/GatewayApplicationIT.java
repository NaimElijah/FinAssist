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
import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
     * One client for the class, closed afterwards. {@link HttpClient} owns a selector thread and
     * an executor and is {@link AutoCloseable} on Java 21, so building one per request would leave
     * it running until the JVM exited.
     */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
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
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        List<Context> webApps = Arrays.stream(server.getTomcat().getHost().findChildren())
                .map(Context.class::cast)
                .toList();
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
