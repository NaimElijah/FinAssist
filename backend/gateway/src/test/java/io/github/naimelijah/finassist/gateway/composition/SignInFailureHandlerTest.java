package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * What a failed sign-in is allowed to write to a log (ADR-030).
 *
 * <p>This is the one place in the gateway where an authorization code or a token could plausibly
 * reach a log line. Keycloak's token endpoint puts its own prose in an error's {@code description},
 * and Spring wraps the whole failed exchange in the {@link OAuth2AuthenticationException} the
 * handler is given — so a handler that logged the exception, as Spring's own {@code
 * SimpleUrlAuthenticationFailureHandler} does, could carry the code that was being redeemed into
 * the log. {@link SignInFailureHandler} logs {@code OAuth2Error#getErrorCode()} and nothing else,
 * and this is what makes that a decision rather than a comment.
 *
 * <p>The appender is attached to the handler's own logger rather than capturing standard output,
 * so the assertion does not depend on whether some other test in this JVM has already let Spring
 * Boot swap the console appender for its ECS one.
 *
 * <p>A full sign-in and relay, with every log line swept for a token, cookie or session id, needs a
 * real Keycloak and the relay routes; that is {@code GatewayLoginFlowIT}'s.
 */
class SignInFailureHandlerTest {

    private static final String WEB_ORIGIN = "http://web.example:5173";

    /**
     * Stands in for what an authorization server can put in a {@code description}: Keycloak quotes
     * the request it refused, so the code being redeemed can appear there.
     */
    private static final String LEAKED_CODE = "3f9c1a7e-code-that-must-never-be-logged";

    private static final String ERROR_CODE = "invalid_grant";

    private final Logger logger = (Logger) LoggerFactory.getLogger(SignInFailureHandler.class);
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

    private static AuthenticationException refusedExchange() {
        OAuth2Error error = new OAuth2Error(
                ERROR_CODE, "Token request failed for code=" + LEAKED_CODE, "https://keycloak.example/error");
        return new OAuth2AuthenticationException(error, error.toString());
    }

    private MockHttpServletResponse failWith(AuthenticationException failure) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bff/login/oauth2/code/keycloak");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SignInFailureHandler(new Origin(WEB_ORIGIN)).onAuthenticationFailure(request, response, failure);
        return response;
    }

    @Test
    void Given_a_failure_quoting_an_authorization_code_When_it_is_logged_Then_only_the_error_code_is_written()
            throws Exception {
        failWith(refusedExchange());

        assertThat(captured.list).hasSize(1);
        ILoggingEvent line = captured.list.getFirst();
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getFormattedMessage()).contains(ERROR_CODE).doesNotContain(LEAKED_CODE);
    }

    /**
     * The exception is never passed to the logger, so nothing downstream of it — an appender that
     * renders a stack trace, a collector that indexes one — can reach the description either. A
     * handler that logged the exception would attach it here, which is what turns this assertion
     * red.
     */
    @Test
    void Given_a_failed_sign_in_When_it_is_logged_Then_the_exception_is_not_attached_to_the_line() throws Exception {
        failWith(refusedExchange());

        assertThat(captured.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void Given_a_failed_sign_in_When_it_is_handled_Then_the_browser_is_sent_to_the_public_origin() throws Exception {
        MockHttpServletResponse response = failWith(refusedExchange());

        assertThat(response.getRedirectedUrl()).isEqualTo(WEB_ORIGIN + "/?signin=failed");
    }

    /**
     * A failure that is not an OAuth2 one still produces a line, and still says nothing: without
     * this branch a {@code null} error code would either throw inside the logging boundary or print
     * whatever the exception carried.
     */
    @Test
    void Given_a_failure_that_is_not_an_oauth2_one_When_it_is_logged_Then_a_line_is_still_written_with_no_detail()
            throws Exception {
        failWith(new AuthenticationException("session store unreachable at 10.0.0.7") {});

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getFormattedMessage())
                .contains("unknown")
                .doesNotContain("10.0.0.7");
    }
}
