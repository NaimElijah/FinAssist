package io.github.naimelijah.finassist.gateway.composition;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * running gateway, an OpenID provider and the relay routes; that is {@code GatewayLoginFlowIT}'s,
 * against its fake provider rather than a real Keycloak.
 */
class SignInFailureHandlerTest {

    private static final String WEB_ORIGIN = "http://web.example:5173";

    /**
     * Stands in for what an authorization server can put in a {@code description}: Keycloak quotes
     * the request it refused, so the code being redeemed can appear there.
     */
    private static final String LEAKED_CODE = "3f9c1a7e-code-that-must-never-be-logged";

    private static final String ERROR_CODE = "invalid_grant";

    /** Distinctive enough that finding it in the log can only mean the caller's text reached it. */
    private static final String CALLERS_MARKER = "text-the-caller-chose";

    /** What a callback's {@code error} parameter can carry: free text, a line break and JSON. */
    private static final String CALLERS_TEXT = CALLERS_MARKER + "\n{\"forged\":\"line\"}";

    /** A registered code in upper case: the same word, but not a code the specifications define. */
    private static final String NEAR_MISS_CODE = "ACCESS_DENIED";

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
        new SignInFailureHandler(new Origin(Origin.Setting.GATEWAY_PUBLIC_URL, WEB_ORIGIN))
                .onAuthenticationFailure(request, response, failure);
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
     * The callback's {@code error} parameter is whatever its sender put in the query, and Spring
     * carries it into the {@link OAuth2Error} as the error code. A code is logged only when it is
     * one the OAuth2 and OpenID Connect specifications or Spring Security define, so a sender can
     * never choose the text of a gateway log line.
     */
    @Test
    void
            Given_a_callback_error_that_is_not_a_registered_oauth2_code_When_the_sign_in_fails_Then_the_log_line_carries_none_of_the_callers_text()
                    throws Exception {
        failWith(new OAuth2AuthenticationException(new OAuth2Error(CALLERS_TEXT)));

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getFormattedMessage())
                .contains("unrecognised")
                .doesNotContain(CALLERS_TEXT)
                .doesNotContain(CALLERS_MARKER);
    }

    /**
     * Each family of registered codes is still logged as itself: the authorization endpoint's
     * (RFC 6749 §4.1.2.1), the token endpoint's (§5.2), OpenID Connect's (Core §3.1.2.6), and the
     * ones Spring Security raises while completing a sign-in.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "access_denied",
                "invalid_client",
                "login_required",
                "authorization_request_not_found",
                "invalid_id_token",
                "invalid_token_response"
            })
    void Given_a_registered_oauth2_error_code_When_the_sign_in_fails_Then_that_code_is_logged(String code)
            throws Exception {
        failWith(new OAuth2AuthenticationException(new OAuth2Error(code)));

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getFormattedMessage()).endsWith(" " + code);
    }

    /**
     * The comparison is exact. A registered code is lower case by definition, so the same word in
     * another case is not one, and is logged as {@code unrecognised} rather than as sent.
     */
    @Test
    void Given_a_registered_code_in_another_case_When_the_sign_in_fails_Then_it_is_logged_as_unrecognised()
            throws Exception {
        failWith(new OAuth2AuthenticationException(new OAuth2Error(NEAR_MISS_CODE)));

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getFormattedMessage())
                .endsWith(" unrecognised")
                .doesNotContain(NEAR_MISS_CODE);
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
