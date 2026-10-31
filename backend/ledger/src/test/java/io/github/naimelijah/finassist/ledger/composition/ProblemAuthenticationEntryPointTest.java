package io.github.naimelijah.finassist.ledger.composition;

import static io.github.naimelijah.finassist.ledger.ProblemAssertions.assertRequiredMembers;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * What {@code ProblemAuthenticationEntryPoint} writes to the log, which is the half of its
 * contract no status code shows.
 *
 * <p>The distinction this pins is the one an on-call rule is built on: a refused token is one
 * caller's problem and stays at WARN, while a key source that cannot be reached means <em>nobody
 * can authenticate at all</em>, which is actionable now and therefore ERROR, under a message of
 * its own rather than the refusal line's. The assistant's {@code BearerAuthentication} draws the
 * same line in the same place, and {@code tests/test_authorization.py} pins it there.
 *
 * <p>Called directly rather than through a running ledger: the entry point takes only the request,
 * the response and the exception, so it needs neither a context nor a database to prove what it
 * logs. {@code LedgerKeySourceOutageIT} drives the real path, against a JWKS source that refuses
 * the connection, and asserts the same level end to end.
 */
class ProblemAuthenticationEntryPointTest {

    /** Stands in for a value that must never reach a log line, whichever branch answers. */
    private static final String TOKEN = "a-bearer-token-that-must-never-be-logged";

    private final ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ProblemAuthenticationEntryPoint.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(captured);
        captured.stop();
    }

    private void commence(AuthenticationException failure) throws IOException {
        entryPoint.commence(request, response, failure);
    }

    private ILoggingEvent onlyLine() {
        assertThat(captured.list).hasSize(1);
        return captured.list.getFirst();
    }

    @Test
    void Given_the_key_source_could_not_be_reached_When_the_request_is_answered_Then_it_is_logged_at_error()
            throws IOException {
        commence(new AuthenticationServiceException("jwks endpoint refused the connection"));

        ILoggingEvent line = onlyLine();
        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getKeyValuePairs())
                .anyMatch(pair ->
                        "reason".equals(pair.key) && ProblemAuthenticationEntryPoint.KEY_SOURCE.equals(pair.value));
    }

    /**
     * Not merely "a different level": the two cases must read differently, because an outage and a
     * refusal call for different actions and a reader scanning messages should not have to compare
     * a structured field to tell them apart.
     *
     * <p>The raw templates are compared, not the formatted lines. Two lines built from one template
     * already differ once a different reason is interpolated into it, so comparing what a reader
     * sees would pass while both cases still shared a message.
     */
    @Test
    void Given_an_outage_and_a_refusal_When_both_are_logged_Then_the_outage_has_a_message_of_its_own()
            throws IOException {
        commence(new AuthenticationServiceException("jwks endpoint refused the connection"));
        commence(new InvalidBearerTokenException("the signature did not verify"));

        assertThat(captured.list).hasSize(2);
        assertThat(captured.list.getFirst().getMessage())
                .isNotEqualTo(captured.list.get(1).getMessage());
    }

    /**
     * The two branches build their bodies apart, one per status, so each is checked for the members every
     * ledger problem carries (ADR-068).
     */
    @Test
    void Given_an_outage_or_a_refused_token_When_answered_Then_each_problem_carries_every_required_member()
            throws Exception {
        MockHttpServletResponse outage = new MockHttpServletResponse();
        MockHttpServletResponse refusal = new MockHttpServletResponse();

        entryPoint.commence(
                request, outage, new AuthenticationServiceException("jwks endpoint refused the connection"));
        entryPoint.commence(request, refusal, new InvalidBearerTokenException("the signature did not verify"));

        assertRequiredMembers(outage);
        assertRequiredMembers(refusal);
    }

    @Test
    void Given_a_refused_token_When_the_request_is_answered_Then_it_stays_at_warn() throws IOException {
        commence(new InvalidBearerTokenException("the signature did not verify"));

        assertThat(onlyLine().getLevel()).isEqualTo(Level.WARN);
    }

    /**
     * The request carries a bearer token in both branches, so neither the message, the structured
     * fields nor an attached throwable may quote it — and no throwable is attached at all, so
     * nothing downstream can render one either.
     */
    @Test
    void Given_either_failure_When_it_is_logged_Then_the_token_never_appears_and_no_throwable_is_attached()
            throws IOException {
        commence(new AuthenticationServiceException("jwks endpoint refused the connection"));
        commence(new InvalidBearerTokenException("the signature did not verify"));

        assertThat(captured.list).allSatisfy(line -> {
            assertThat(line.getFormattedMessage()).doesNotContain(TOKEN);
            assertThat(line.getKeyValuePairs()).noneMatch(pair -> TOKEN.equals(pair.value));
            assertThat(line.getThrowableProxy()).isNull();
        });
    }
}
