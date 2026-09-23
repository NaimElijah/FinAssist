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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;

/**
 * The branch of {@link LoginEntryFailureHandler} no running gateway can provoke: a sign-in that
 * could not be started for some reason other than an unknown registration id.
 *
 * <p>The refusal itself — the case that matters, and the defect this handler was written for — is
 * proved by {@code GatewaySecurityTest}, which drives a real unknown segment through the real
 * filter and so asserts against Spring's real, package-private {@code
 * InvalidClientRegistrationIdException} rather than a stand-in. That exception cannot be
 * constructed from here, which is exactly why the refusal is proved there and not in this class.
 *
 * <p>What is left is the catch-all, and it is asserted because "fail loudly" is a claim: a wrong
 * status, a message quoted into a log line or a stack trace attached to one would all pass
 * unnoticed otherwise. The appender is attached to the handler's own logger rather than capturing
 * standard output, for {@code SignInFailureHandlerTest}'s reason.
 */
class LoginEntryFailureHandlerTest {

    /** Stands in for what an unexpected failure's message could carry into a log line. */
    private static final String LEAKED_DETAIL = "keycloak.internal:8080 refused the connection";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoginEntryFailureHandler.class);
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

    /** The shape the redirect filter hands its failure handler: a wrapper around the real cause. */
    private static AuthenticationException wrapping(Throwable cause) {
        return new AuthenticationException(cause.getMessage(), cause) {};
    }

    private MockHttpServletResponse failWith(AuthenticationException failure) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bff/oauth2/authorization/keycloak");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LoginEntryFailureHandler().onAuthenticationFailure(request, response, failure);
        return response;
    }

    @Test
    void Given_a_cause_that_is_not_an_unknown_registration_id_When_it_is_handled_Then_it_answers_500_internal_error()
            throws Exception {
        MockHttpServletResponse response = failWith(wrapping(new IllegalStateException(LEAKED_DETAIL)));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"INTERNAL_ERROR\"");
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void Given_an_unexpected_cause_When_it_is_logged_Then_only_its_class_name_is_written() throws Exception {
        failWith(wrapping(new IllegalStateException(LEAKED_DETAIL)));

        assertThat(captured.list).hasSize(1);
        ILoggingEvent line = captured.list.getFirst();
        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getFormattedMessage())
                .contains(IllegalStateException.class.getName())
                .doesNotContain(LEAKED_DETAIL);
        assertThat(line.getThrowableProxy()).isNull();
    }

    /**
     * A failure carrying no cause at all is still answered and still logged, rather than reaching
     * {@code getClass()} on a {@code null} inside the logging boundary.
     */
    @Test
    void Given_a_failure_carrying_no_cause_When_it_is_handled_Then_it_still_answers_500_and_names_no_cause()
            throws Exception {
        MockHttpServletResponse response = failWith(new AuthenticationException(LEAKED_DETAIL) {});

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getFormattedMessage())
                .contains("none")
                .doesNotContain(LEAKED_DETAIL);
    }
}
