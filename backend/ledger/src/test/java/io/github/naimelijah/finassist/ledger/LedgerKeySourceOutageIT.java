package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A JWKS source that refuses the connection outright must answer {@code 503} {@code
 * AUTHENTICATION_UNAVAILABLE}, never {@code 401} and never a bare {@code 500} — nothing here says
 * whether the token presented would have been valid (ADR-030).
 *
 * <p>Redirects {@link PostgresFixture#jwks} to a closed port via {@link
 * PostgresFixture#overrideJwksUri}, found by opening a {@link ServerSocket} on an ephemeral port
 * and closing it immediately: nothing listens there afterwards, so the connection is refused
 * deterministically and fast, rather than timing out against a merely slow or firewalled address.
 * {@link #restoreJwks()} undoes the redirect once this class is done, since the override is a
 * static field the whole fork shares.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LedgerKeySourceOutageIT extends PostgresFixture {

    /**
     * Exists as much for its side effect on the test-context cache as for its body: Spring keys a
     * cached {@code ApplicationContext} partly by the <em>set</em> of {@code @DynamicPropertySource}
     * methods a test class contributes, not by what they return, so a class that inherited only
     * {@code PostgresFixture}'s own two methods would be handed back any already-built context of
     * the same configuration — a MOCK {@code @SpringBootTest} with {@code @AutoConfigureMockMvc} and
     * nothing more — {@code JwtDecoder} bean and all, whose JWKS URI was resolved long before {@link
     * #pointJwksAtAClosedPort} ever ran. {@code LedgerAuthorizationIT} was that context until it
     * gained {@code @AutoConfigureMetrics}; this method keeps the outage test from depending on a
     * sibling's annotations happening to differ. Declaring one more method here is what forces a
     * context built fresh for this class, so the override below is actually read while the decoder
     * is being constructed rather than after.
     */
    @DynamicPropertySource
    static void forceAFreshContext(DynamicPropertyRegistry registry) {
        registry.add("finassist.security.outage-test-marker", () -> "true");
    }

    @BeforeAll
    static void pointJwksAtAClosedPort() {
        overrideJwksUri(closedPortJwksUri());
    }

    @AfterAll
    static void restoreJwks() {
        restoreJwksUri();
    }

    private static String closedPortJwksUri() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/certs";
        } catch (IOException e) {
            throw new IllegalStateException("could not reserve a port to close for the outage test", e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void Given_the_jwks_source_is_unreachable_When_a_token_is_presented_Then_it_is_503_authentication_unavailable()
            throws Exception {
        // MockMvc sets no Host header of its own, and HostAllowListFilter runs ahead of the
        // security chain in this full-context test, so without one this would be refused 403
        // HOST_NOT_ALLOWED before authentication is even attempted.
        mvc.perform(get("/api/v1/accounts")
                        .header(HttpHeaders.HOST, "localhost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.owner()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));
    }

    /**
     * The same outage, seen from the log rather than the response: an unreachable key source means
     * nobody can authenticate at all, so it is ERROR under its own message rather than one more
     * WARN about a refused token. {@code ProblemAuthenticationEntryPointTest} pins the level and
     * the message directly; this proves the real path reaches that branch, through a real decoder
     * against a real closed port, and that the token still never reaches a line.
     */
    @Test
    void Given_the_jwks_source_is_unreachable_When_a_token_is_presented_Then_the_outage_is_logged_at_error(
            CapturedOutput output) throws Exception {
        String token = TestTokens.owner();

        mvc.perform(get("/api/v1/accounts")
                .header(HttpHeaders.HOST, "localhost")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        String outageLine = output.getOut()
                .lines()
                .filter(candidate -> candidate.contains("key_source"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line was captured for the key source outage"));

        assertThat(outageLine)
                .contains("\"level\":\"ERROR\"")
                .contains("Could not reach the authentication key source");
        assertThat(output.getOut()).doesNotContain(token);
    }
}
