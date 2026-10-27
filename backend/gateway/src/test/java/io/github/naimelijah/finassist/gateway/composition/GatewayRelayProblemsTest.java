package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link GatewayRelayProblems#relayFailed} answers for a failure that is neither an {@code
 * OAuth2AuthorizationException} nor a {@code ResourceAccessException} — the one branch
 * {@code GatewayLoginFlowIT}'s relay tests never reach, so this is its standing regression test.
 *
 * <p>Called directly rather than through a running gateway and a fault-injecting fake: {@code
 * relayFailed} takes only the exception and the response, so it needs neither a route nor a
 * Spring context to prove what it does — the same reasoning, and the same technique, {@code
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
}
