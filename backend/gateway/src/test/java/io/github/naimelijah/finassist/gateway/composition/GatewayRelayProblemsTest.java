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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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
 * and how {@link GatewayRelayProblems#upstreamFailed} tells a connection never opened from a
 * request whose outcome is unknown (ADR-067), and what {@link GatewayRelayProblems#authorizationFailed}
 * logs and answers when a relay's authorization cannot be renewed.
 *
 * <p>Called directly rather than through a running gateway and a fault-injecting fake: each
 * handler takes only the exception, the response and at most a request holding the session, so it
 * needs neither a route nor a Spring context to prove what it does — the same reasoning, and the
 * same technique, {@code SignInFailureHandlerTest} already uses for the one other place in this
 * service where an unexpected value could reach a log line.
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

    /** The registration {@code KeycloakRegistration} declares, as a refused authorization names it. */
    private static final String REGISTRATION_ID = "keycloak";

    /**
     * Stands in for what Keycloak can put in a refused refresh's {@code description}: prose about
     * the token being redeemed, which must never reach a log line.
     */
    private static final String REFUSAL_DESCRIPTION = "Token is not active: refresh-token-that-must-never-be-logged";

    /**
     * What spring-security 7.1.2's {@code AbstractRestClientOAuth2AccessTokenResponseClient} puts in
     * the {@code invalid_token_response} it raises for a {@code RestClientException}: the wrapped
     * exception's message, which names the token endpoint's URL.
     */
    private static final String UNUSABLE_PROVIDER_DESCRIPTION =
            "An error occurred while attempting to retrieve the OAuth 2.0 Access Token Response: I/O error on POST"
                    + " request for \"http://keycloak:8080/realms/finassist/protocol/openid-connect/token\"";

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

    /** Keycloak refusing the refresh grant, as {@code RefreshTokenOAuth2AuthorizedClientProvider} wraps it. */
    private static OAuth2AuthorizationException refusedRefresh(String code) {
        return new ClientAuthorizationException(
                new OAuth2Error(code, REFUSAL_DESCRIPTION, "https://keycloak.example/error"), REGISTRATION_ID);
    }

    /** The token endpoint could not be used at all: unreachable, a 5xx, or no token response. */
    private static OAuth2AuthorizationException unusableIdentityProvider() {
        return new OAuth2AuthorizationException(
                new OAuth2Error("invalid_token_response", UNUSABLE_PROVIDER_DESCRIPTION, null),
                new ResourceAccessException(UNUSABLE_PROVIDER_DESCRIPTION, new ConnectException()));
    }

    private void authorizationFailed(OAuth2AuthorizationException failure) throws IOException {
        problems.authorizationFailed(failure, new MockHttpServletRequest(), response);
    }

    @Test
    void Given_a_session_whose_refresh_was_refused_When_it_is_answered_Then_the_session_is_invalidated_and_it_is_a_401()
            throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        problems.authorizationFailed(refusedRefresh("invalid_grant"), request, response);

        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(PROBLEM_JSON);
        assertThat(JSON.readTree(response.getContentAsByteArray()).path("code").asString())
                .isEqualTo("UNAUTHENTICATED");
    }

    /**
     * A routine expiry and an outage must be told apart from the log alone, so the line names the
     * OAuth2 error code, and only the code: the description is Keycloak's prose and is never
     * written, nor the exception attached.
     */
    @Test
    void Given_a_refresh_keycloak_refused_When_it_is_logged_Then_the_warn_names_its_error_code_and_nothing_else()
            throws IOException {
        authorizationFailed(refusedRefresh("invalid_grant"));

        assertThat(captured.list).hasSize(1);
        ILoggingEvent line = captured.list.getFirst();
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getFormattedMessage())
                .contains("A relay's authorization could not be renewed")
                .contains("invalid_grant")
                .doesNotContain(REFUSAL_DESCRIPTION);
        assertThat(line.getThrowableProxy()).isNull();
    }

    /**
     * {@code invalid_token_response} is Spring's code for a token endpoint it could not use (down,
     * answering a 5xx, or refusing the gateway's own client credentials), so every session's refresh
     * fails, not one owner's. That is an outage: ERROR, in a line of its own, carrying the code and
     * never the description, which names the endpoint's URL.
     */
    @Test
    void
            Given_an_identity_provider_that_could_not_be_used_When_a_refresh_fails_Then_it_is_an_error_in_a_line_of_its_own()
                    throws IOException {
        authorizationFailed(unusableIdentityProvider());
        authorizationFailed(refusedRefresh("invalid_grant"));

        assertThat(captured.list).hasSize(2);
        ILoggingEvent outage = captured.list.get(0);
        assertThat(outage.getLevel()).isEqualTo(Level.ERROR);
        assertThat(outage.getFormattedMessage())
                .contains("A relay's authorization could not be renewed")
                .contains("invalid_token_response")
                .doesNotContain("keycloak:8080")
                .doesNotContain("Access Token Response");
        assertThat(outage.getThrowableProxy()).isNull();
        assertThat(outage.getMessage())
                .as("the outage's message pattern, apart from the refusal's")
                .isNotEqualTo(captured.list.get(1).getMessage());
    }

    /**
     * A relay whose session no longer holds an authorized client (removed by a concurrent request's
     * refused refresh) is refused by the {@code authorization_code} provider with Spring's own
     * {@code client_authorization_required}, which is part of the registered vocabulary.
     */
    @Test
    void Given_a_session_holding_no_authorized_client_When_a_relay_is_refused_Then_its_spring_code_is_logged()
            throws IOException {
        authorizationFailed(new ClientAuthorizationRequiredException(REGISTRATION_ID));

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(captured.list.getFirst().getFormattedMessage()).contains("client_authorization_required");
    }

    /**
     * The code in a refused refresh is whatever the token endpoint's error body said, so it is held
     * to the same registered vocabulary a failed sign-in is.
     */
    @Test
    void Given_a_refusal_carrying_an_unregistered_code_When_it_is_logged_Then_it_is_logged_as_unrecognised()
            throws IOException {
        authorizationFailed(refusedRefresh("refresh-token-that-must-never-be-logged"));

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getFormattedMessage())
                .contains("unrecognised")
                .doesNotContain("must-never-be-logged");
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
     * the relayed URL and its query, and is never written, nor the exception attached. Each line
     * says what its own failure means to whoever reads it, so swapping them would mislead: a
     * timeout's outcome is unknown, while a connection never opened sent nothing (ADR-067).
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
                .as("the read timeout's line")
                .contains("did not answer in time")
                .contains("outcome is unknown")
                .doesNotContain("could not be reached");
        assertThat(captured.list.get(1).getFormattedMessage())
                .as("the unopened connection's line")
                .contains("could not be reached")
                .doesNotContain("in time")
                .doesNotContain("outcome is unknown");
    }
}
