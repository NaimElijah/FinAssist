package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Turns a failure raised while relaying a request to the ledger or the assistant into an RFC 9457
 * problem, so the browser sees a status it can act on rather than a bare {@code 500} or a
 * container error page (ADR-030).
 *
 * <p><b>Why an advice sees these at all.</b> Server Web MVC's routes are ordinary
 * {@code RouterFunction} beans dispatched through the same {@code DispatcherServlet} as a
 * {@code @RestController} would be: a route's {@code HandlerFunction} runs inside {@code
 * DispatcherServlet#doDispatch}'s try block, so an exception it throws — building the proxied
 * request, refreshing a token, or opening the outbound connection — reaches {@code
 * processHandlerException} and, through it, {@code ExceptionHandlerExceptionResolver}, exactly as
 * it would for any {@code @Controller}. This was confirmed empirically: before this class existed,
 * a refused connection surfaced as a bare, unproblemed failure past the dispatcher; adding the
 * {@code @ExceptionHandler} methods below turned it into the {@code UPSTREAM_UNAVAILABLE} problem
 * asserted by {@code GatewayLoginFlowIT}, and a stalled upstream into the {@code UPSTREAM_TIMEOUT}
 * problem asserted by {@code GatewayRelayTimeoutIT}. A downstream {@code 4xx}/{@code 5xx} response, by
 * contrast, never reaches this class: {@code RestClientProxyExchange} calls {@code RestClient}'s
 * low-level {@code exchange(...)}, which applies no default status handler, so the ledger's or the
 * assistant's own problem body passes through this gateway untouched.
 *
 * <p><b>Refresh and authorization failures.</b> {@link TokenRelayFilterFunctions#tokenRelay()}
 * calls {@code OAuth2AuthorizedClientManager#authorize}, which — on a refused {@code
 * refresh_token} grant — wraps Keycloak's error as a {@code ClientAuthorizationException} (a
 * subtype of {@link OAuth2AuthorizationException}, itself a plain {@code RuntimeException}, not an
 * {@code AuthenticationException}: it is thrown deep inside the dispatcher's handler execution,
 * not by anything {@code ExceptionTranslationFilter} would recognise). A stale or unusable
 * authorized client is exactly the situation this gateway's session must not keep, so the session
 * is invalidated — discarding the access and refresh tokens with it — and the browser is told
 * {@code 401}, never redirected: the same contract {@link ProblemAuthenticationEntryPoint} gives
 * every other unauthenticated request.
 *
 * <p><b>A refusal Spring MVC raised is not a gateway failure.</b> {@code GET /bff/me} is the one
 * handler method this service owns, so the dispatcher can raise exactly two refusals of its own:
 * {@code 405} for another method on that path, and {@code 406} for an {@code Accept} header it
 * produces nothing for. Both arrive here as {@link ErrorResponse} subtypes, and both are answered
 * at their own status, at WARN, because the request is what was wrong. Every other status stays
 * with the catch-all below — a relay route's own methods are fixed by its {@code Method=}
 * predicate and every other path is refused before the dispatcher, so no third status can reach
 * this class without something in this gateway having gone wrong.
 *
 * <p><b>Not every refusal the gateway makes passes through here, though.</b> A request refused
 * before the dispatcher writes its own body: {@link HostAllowListFilter}, {@link
 * ProblemAuthenticationEntryPoint}, {@link ProblemAccessDeniedHandler} and {@link
 * LoginEntryFailureHandler} each do. That last one is the reason this paragraph says "before the
 * dispatcher" rather than "by the security chain": a login entry naming a registration that does
 * not exist is refused by {@code OAuth2AuthorizationRequestRedirectFilter}, which runs ahead of
 * the chain's own authorization rules — and, until that handler was given to it, answered {@code
 * 500} with Boot's error body instead, reaching neither this class nor any code {@link
 * GatewayProblems} declares.
 *
 * <p><b>Nothing here logs a token.</b> The refresh failure is logged by its OAuth2 error code
 * alone, held to the same {@link OAuth2ErrorVocabulary} {@link SignInFailureHandler} uses, for the
 * same reason: Keycloak's error description can quote the request that was refused. The catch-all
 * for anything else logs the exception's class name alone, never its message or its stack trace,
 * because an arbitrary unexpected failure is the one case this class cannot enumerate the safety
 * of.
 */
@RestControllerAdvice
final class GatewayRelayProblems {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayRelayProblems.class);

    private static final String REFRESH_DETAIL =
            "The session's authorization could not be renewed. Sign in again at " + BffRoutes.LOGIN_ENTRY + '.';

    private static final byte[] REFRESH_FAILED_BODY =
            GatewayProblems.write(HttpStatus.UNAUTHORIZED, REFRESH_DETAIL, GatewayProblems.UNAUTHENTICATED);

    private static final String UPSTREAM_DETAIL = "The upstream service could not be reached.";

    private static final byte[] UPSTREAM_UNAVAILABLE_BODY =
            GatewayProblems.write(HttpStatus.BAD_GATEWAY, UPSTREAM_DETAIL, GatewayProblems.UPSTREAM_UNAVAILABLE);

    /**
     * Read by the owner as it stands: the web client shows a problem's detail verbatim, and this is
     * the one answer after which resending a change can duplicate it in the append-only journal.
     */
    private static final String TIMEOUT_DETAIL = "The upstream service did not answer in time, so the outcome is"
            + " unknown: a change you sent may already have been applied. Check before sending it again.";

    private static final byte[] UPSTREAM_TIMEOUT_BODY =
            GatewayProblems.write(HttpStatus.GATEWAY_TIMEOUT, TIMEOUT_DETAIL, GatewayProblems.UPSTREAM_TIMEOUT);

    private static final String INTERNAL_DETAIL = "The gateway failed unexpectedly.";

    private static final byte[] INTERNAL_ERROR_BODY =
            GatewayProblems.write(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_DETAIL, GatewayProblems.INTERNAL_ERROR);

    private static final String METHOD_NOT_ALLOWED_DETAIL = "This path answers no such method.";

    private static final String NOT_ACCEPTABLE_DETAIL = "This path produces no representation this request accepts.";

    /**
     * The statuses a framework refusal is answered at, with the body each carries. A status absent
     * from this map is not something the gateway's own routes can raise, so it is treated as the
     * failure it would be rather than passed through with a code invented on the spot.
     */
    private static final Map<HttpStatus, byte[]> FRAMEWORK_REFUSALS = Map.of(
            HttpStatus.METHOD_NOT_ALLOWED,
                    GatewayProblems.write(
                            HttpStatus.METHOD_NOT_ALLOWED,
                            METHOD_NOT_ALLOWED_DETAIL,
                            GatewayProblems.METHOD_NOT_ALLOWED),
            HttpStatus.NOT_ACCEPTABLE,
                    GatewayProblems.write(
                            HttpStatus.NOT_ACCEPTABLE, NOT_ACCEPTABLE_DETAIL, GatewayProblems.NOT_ACCEPTABLE));

    /**
     * A relay's authorization could not be renewed: the refresh token Keycloak was asked to redeem
     * was refused, or the manager could not authorize the relay for some other reason.
     *
     * <p>Logged by its error code alone. A refusal, such as {@code invalid_grant} for an expired
     * session, is WARN: it concerns one session. {@code invalid_token_response} is ERROR, in a
     * message of its own: Spring raises it when the token endpoint could not be used at all, so
     * every session's refresh is failing, the same outage the resource servers log for an unusable
     * key source.
     *
     * @param failure the manager's refusal, whose error code is the only part of it logged
     * @param request carries the session to invalidate; absent when none exists, which is not an
     *     error — there is nothing left to discard
     * @param response the response to answer, since a filter later in the chain may not write to
     *     it again
     * @throws IOException if the problem body cannot be written
     */
    @ExceptionHandler(OAuth2AuthorizationException.class)
    void authorizationFailed(
            OAuth2AuthorizationException failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String code = OAuth2ErrorVocabulary.loggableCode(failure.getError());
        if (OAuth2ErrorVocabulary.INVALID_TOKEN_RESPONSE.equals(code)) {
            LOG.error(
                    "A relay's authorization could not be renewed: the identity provider's token endpoint was"
                            + " unusable, OAuth2 error code {}; invalidating the session",
                    code);
        } else {
            LOG.warn(
                    "A relay's authorization could not be renewed with OAuth2 error code {}; invalidating the session",
                    code);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        GatewayProblems.send(response, HttpStatus.UNAUTHORIZED, REFRESH_FAILED_BODY);
    }

    /**
     * The relay's outbound call to the ledger or the assistant failed with an I/O error — {@code
     * RestClient}'s wrapper for the underlying {@code IOException} — and the answer depends on
     * whether the request was sent (ADR-067).
     *
     * <p>A read timeout is {@code 504 UPSTREAM_TIMEOUT}: the upstream received the request and may
     * have committed it, so the detail says the outcome is unknown rather than that nothing was
     * reached. Anything else — a refused or unresolvable connection, or a connect timeout — is
     * {@code 502 UPSTREAM_UNAVAILABLE}, as is an I/O failure this class cannot place, whose chain does
     * not say whether the request was written: a reset after sending is one, and #407 tracks answering
     * it as outcome unknown. Each is logged at WARN by a fixed line: the wrapper's
     * message names the relayed URL, query included, so it is never written.
     *
     * @param failure the wrapper whose cause chain says which failure this was
     * @param response the response to answer
     * @throws IOException if the problem body cannot be written
     */
    @ExceptionHandler(ResourceAccessException.class)
    void upstreamFailed(ResourceAccessException failure, HttpServletResponse response) throws IOException {
        if (timedOutAwaitingAnswer(failure)) {
            LOG.warn("A relay's upstream service did not answer in time; the request's outcome is unknown");
            GatewayProblems.send(response, HttpStatus.GATEWAY_TIMEOUT, UPSTREAM_TIMEOUT_BODY);
            return;
        }
        LOG.warn("A relay's upstream service could not be reached");
        GatewayProblems.send(response, HttpStatus.BAD_GATEWAY, UPSTREAM_UNAVAILABLE_BODY);
    }

    /**
     * Whether the request was sent and then went unanswered past the read budget. Spring's {@code
     * JdkClientHttpRequest} raises a read timeout as a bare {@link HttpTimeoutException}; the JDK
     * raises a connect timeout as {@link HttpConnectTimeoutException}, which extends it, so the
     * subtype is tested first. The first timeout in the chain decides, and the walk stops at a
     * cause it has already seen, since {@code initCause} can close a loop.
     */
    private static boolean timedOutAwaitingAnswer(ResourceAccessException failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The browser went away while its answer was being written: spring-webmvc's {@code
     * HandlerFunctionAdapter} wraps every response, and the wrapper raises a failed write to the
     * client as this exception, the relayed body's copy included. Nobody is left to read an answer,
     * the response is usually committed already, and nothing in the gateway failed, so nothing is
     * written and the line is DEBUG, as the ledger's {@code ResponseEntityExceptionHandler} answers
     * the same exception.
     *
     * <p>Matched by type rather than by Spring's {@code DisconnectedClientHelper}, which also
     * claims an {@code EOFException}: on a relay that can be a truncated upstream body, a failure
     * the catch-all must still report.
     *
     * @param response declared, and deliberately left untouched, so Spring treats the failure as
     *     handled and renders nothing in its place
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientDisconnected(HttpServletResponse response) {
        LOG.debug("A relay's client disconnected before its answer was written");
    }

    /**
     * Anything else a relay raised, and — because {@code @ExceptionHandler(Exception.class)} has
     * no narrower reach than "this advice" — anything else any other handler in this gateway
     * raises too, unclaimed by a more specific one. That is deliberate rather than an
     * overreach: it is the same role the ledger's composition-root fallback plays, answering
     * everything nothing more specific claimed, at the lowest precedence Spring gives an
     * {@code @ExceptionHandler} match.
     *
     * <p>A framework refusal is peeled off first, at its own status, with its own headers ({@code
     * Allow} on a 405) and at WARN. Everything left
     * is a 500, logged at ERROR by class name alone — never the message, never the stack trace —
     * because that branch cannot enumerate what an arbitrary exception's text might quote. A
     * status is read only from an {@link ErrorResponse}, whose contract is to carry one; the
     * exception's own type is never mapped to a status here.
     *
     * @throws IOException if the problem body cannot be written
     */
    @ExceptionHandler(Exception.class)
    void relayFailed(Exception failure, HttpServletResponse response) throws IOException {
        if (failure instanceof ErrorResponse framework) {
            HttpStatus status = HttpStatus.resolve(framework.getStatusCode().value());
            byte[] refusal = status == null ? null : FRAMEWORK_REFUSALS.get(status);
            if (refusal != null) {
                LOG.warn("Refused a request no handler here answers: {}", status.value());
                // The framework's headers are part of the refusal: a 405 must carry Allow (RFC 9110 §15.5.6).
                framework
                        .getHeaders()
                        .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
                GatewayProblems.send(response, status, refusal);
                return;
            }
        }
        LOG.error("A relay failed unexpectedly: {}", failure.getClass().getName());
        GatewayProblems.send(response, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_BODY);
    }
}
