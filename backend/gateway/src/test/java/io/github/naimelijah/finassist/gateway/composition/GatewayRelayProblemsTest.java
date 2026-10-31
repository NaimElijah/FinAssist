package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link GatewayRelayProblems#relayFailed} answers for a failure that is neither an {@code
 * OAuth2AuthorizationException} nor a {@code ResourceAccessException} — the one branch
 * {@code GatewayLoginFlowIT}'s relay tests never reach, so this is its standing regression test —
 * and how {@link GatewayRelayProblems#upstreamFailed} tells a request the upstream never received
 * from one whose outcome is unknown (ADR-067).
 *
 * <p>Called directly rather than through a running gateway and a fault-injecting fake: each
 * handler takes only the exception and the response, so it needs neither a route nor a Spring
 * context to prove what it does — the same reasoning, and the same technique, {@code
 * SignInFailureHandlerTest} already uses for the one other place in this service where an
 * unexpected value could reach a log line.
 *
 * <p>{@code IllegalStateException} stands in for "anything else": nothing about this handler's
 * behaviour depends on which unmapped exception type arrives, since the catch-all branch of
 * {@code relayFailed} branches on nothing but calling it.
 *
 * <p>The refusals Spring MVC raises are different, and are named exactly: each carries its own
 * status, and answering one as a 500 would both mislead the caller and log a caller's mistake at
 * a level that means "a person is needed now".
 */
class GatewayRelayProblemsTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Stands in for what an arbitrary, unanticipated failure could carry in its message. */
    private static final String SENSITIVE_MESSAGE =
            "duplicate key value violates unique constraint \"authorized_client_pkey\"";

    private static final JsonMapper JSON = JsonMapper.shared();

    /**
     * What {@code RestClient} puts in a {@code ResourceAccessException}'s message: the method and
     * the URL, query included, which a request can carry anything in.
     */
    private static final String RELAYED_REQUEST_MESSAGE =
            "I/O error on POST request for \"http://ledger:8080/api/v1/transactions?memo=rent-to-landlord\"";

    private final GatewayRelayProblems problems = new GatewayRelayProblems();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GatewayRelayProblems.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(captured);
        captured.stop();
    }

    private void relayFailedWithSensitiveMessage() throws IOException {
        problems.relayFailed(new IllegalStateException(SENSITIVE_MESSAGE), response);
    }

    @Test
    void Given_an_exception_that_is_neither_mapped_type_When_a_relay_raises_it_Then_it_answers_500_internal_error()
            throws IOException {
        relayFailedWithSensitiveMessage();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        JsonNode problem = JSON.readTree(response.getContentAsByteArray());
        assertThat(problem.path("status").asInt()).isEqualTo(500);
        assertThat(problem.path("code").asString()).isEqualTo("INTERNAL_ERROR");
    }

    /**
     * The response body is a fixed constant, built once from a detail that says nothing about
     * any particular failure — so this holds a stronger guarantee than "class name only": the
     * body carries neither the message nor even the class name of whatever was thrown.
     */
    @Test
    void
            Given_an_exception_carrying_a_sensitive_message_When_it_is_answered_Then_the_body_names_neither_it_nor_the_exception_type()
                    throws IOException {
        relayFailedWithSensitiveMessage();

        assertThat(response.getContentAsString())
                .doesNotContain(SENSITIVE_MESSAGE)
                .doesNotContain("IllegalStateException");
    }

    @Test
    void Given_an_exception_carrying_a_sensitive_message_When_it_is_logged_Then_only_its_class_name_is_written()
            throws IOException {
        relayFailedWithSensitiveMessage();

        assertThat(captured.list).hasSize(1);
        ILoggingEvent line = captured.list.getFirst();
        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getFormattedMessage())
                .contains(IllegalStateException.class.getName())
                .doesNotContain(SENSITIVE_MESSAGE);
    }

    /**
     * The exception is never passed to the logger, so nothing downstream of it — an appender
     * that renders a stack trace, a collector that indexes one — can reach the message either.
     */
    @Test
    void Given_a_failure_When_it_is_logged_Then_no_throwable_is_attached_to_the_line() throws IOException {
        relayFailedWithSensitiveMessage();

        assertThat(captured.list.getFirst().getThrowableProxy()).isNull();
    }

    /**
     * The two refusals Spring MVC can raise against {@code GET /bff/me}, the gateway's one handler
     * method. {@code GatewaySecurityTest} drives both through the running chain; this pins what
     * the handler does with them, including the status a wrong answer would hide.
     */
    @ParameterizedTest
    @MethodSource("frameworkRefusals")
    void Given_a_refusal_spring_raised_When_it_is_answered_Then_it_keeps_its_own_status_and_code(
            Exception refusal, int status, String code) throws IOException {
        problems.relayFailed(refusal, response);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        JsonNode problem = JSON.readTree(response.getContentAsByteArray());
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asString()).isEqualTo(code);
    }

    /**
     * Only a true 5xx is ERROR. A 4xx says the request was wrong, and a log level that called it a
     * gateway failure would page someone for a caller's mistake.
     */
    @ParameterizedTest
    @MethodSource("refusalsSpringRaises")
    void Given_a_refusal_spring_raised_When_it_is_logged_Then_it_is_warn_rather_than_error(Exception refusal)
            throws IOException {
        problems.relayFailed(refusal, response);

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    private static Stream<Arguments> frameworkRefusals() {
        return Stream.of(
                Arguments.of(new HttpRequestMethodNotSupportedException("POST"), 405, "METHOD_NOT_ALLOWED"),
                Arguments.of(new HttpMediaTypeNotAcceptableException("text/plain"), 406, "NOT_ACCEPTABLE"));
    }

    /** The same refusals, for the assertions that care only about which exception arrived. */
    private static Stream<Exception> refusalsSpringRaises() {
        return frameworkRefusals().map(arguments -> (Exception) arguments.get()[0]);
    }

    /**
     * A status this gateway serves no handler capable of raising is treated as the failure it
     * would be, rather than passed through with a code invented for it. Nothing here can raise a
     * 409 today; if something ever does, that is a defect, and it is answered as one.
     */
    @Test
    void Given_an_error_response_at_an_unmapped_status_When_it_is_answered_Then_it_is_still_a_500() throws IOException {
        problems.relayFailed(new ErrorResponseException(HttpStatus.CONFLICT), response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        assertThat(JSON.readTree(response.getContentAsByteArray()).path("code").asString())
                .isEqualTo("INTERNAL_ERROR");
        assertThat(captured.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
    }

    /**
     * A read timeout, as spring-web 7.0.10's {@code JdkClientHttpRequest} raises it: its timeout
     * handler cancels the exchange at the read budget and rethrows the cancellation as a bare
     * {@code HttpTimeoutException}. The request was sent, so whether it took effect is unknown.
     */
    private static ResourceAccessException readTimeout() {
        return new ResourceAccessException(RELAYED_REQUEST_MESSAGE, new HttpTimeoutException("Request cancelled"));
    }

    /**
     * The three ways the JDK client fails to open a connection, each in the shape it was observed
     * to reach the advice in. A connect timeout is an {@code HttpTimeoutException} too, which is
     * why it is here: nothing was sent, so it is not a timeout whose outcome is unknown.
     */
    private static Stream<Arguments> connectionsNeverOpened() {
        HttpConnectTimeoutException connectTimeout = new HttpConnectTimeoutException("HTTP connect timed out");
        connectTimeout.initCause(new ConnectException("HTTP connect timed out"));
        ConnectException refused = new ConnectException();
        refused.initCause(new ClosedChannelException());
        ConnectException unresolvable = new ConnectException();
        unresolvable.initCause(new UnresolvedAddressException());
        return Stream.of(
                Arguments.of("a connect timeout", new ResourceAccessException(RELAYED_REQUEST_MESSAGE, connectTimeout)),
                Arguments.of("a refused connection", new ResourceAccessException(RELAYED_REQUEST_MESSAGE, refused)),
                Arguments.of(
                        "an unresolvable host", new ResourceAccessException(RELAYED_REQUEST_MESSAGE, unresolvable)));
    }

    @Test
    void Given_a_read_timeout_When_the_relay_fails_Then_it_answers_504_upstream_timeout() throws IOException {
        problems.upstreamFailed(readTimeout(), response);

        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        JsonNode problem = JSON.readTree(response.getContentAsByteArray());
        assertThat(problem.path("status").asInt()).isEqualTo(504);
        assertThat(problem.path("code").asString()).isEqualTo("UPSTREAM_TIMEOUT");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionsNeverOpened")
    void Given_a_connection_never_opened_When_the_relay_fails_Then_it_answers_502_upstream_unavailable(
            String scenario, ResourceAccessException failure) throws IOException {
        problems.upstreamFailed(failure, response);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        JsonNode problem = JSON.readTree(response.getContentAsByteArray());
        assertThat(problem.path("status").asInt()).isEqualTo(502);
        assertThat(problem.path("code").asString()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    /**
     * A chain that loops back on itself cannot be built through a constructor, but {@code
     * initCause} allows it, and a walk that followed it would hold a request thread for ever. The
     * timeout runs the test on a thread of its own, so a walk that never ends fails it rather than
     * hanging the build.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void Given_a_cause_chain_that_loops_When_the_relay_fails_Then_it_answers_502_rather_than_walking_for_ever()
            throws IOException {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);

        problems.upstreamFailed(new ResourceAccessException(RELAYED_REQUEST_MESSAGE, first), response);

        assertThat(response.getStatus()).isEqualTo(502);
    }

    /**
     * The two failures are logged apart, at WARN, by a fixed line: the exception's message names
     * the relayed URL and its query, and is never written, nor the exception attached.
     */
    @Test
    void Given_a_read_timeout_and_a_connection_never_opened_When_each_is_logged_Then_each_is_a_distinct_fixed_warn()
            throws IOException {
        problems.upstreamFailed(readTimeout(), response);
        problems.upstreamFailed(
                new ResourceAccessException(RELAYED_REQUEST_MESSAGE, new ConnectException()),
                new MockHttpServletResponse());

        assertThat(captured.list).hasSize(2);
        assertThat(captured.list).allSatisfy(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).doesNotContain("ledger:8080").doesNotContain("memo");
            assertThat(line.getThrowableProxy()).isNull();
        });
        assertThat(captured.list.get(0).getFormattedMessage())
                .isNotEqualTo(captured.list.get(1).getFormattedMessage());
    }
}
