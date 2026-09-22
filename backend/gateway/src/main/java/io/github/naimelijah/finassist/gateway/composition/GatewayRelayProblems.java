package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

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
 * asserted by {@code GatewayLoginFlowIT}. A downstream {@code 4xx}/{@code 5xx} response, by
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
 * predicate and every other path is refused by the security chain long before the dispatcher, so
 * no third status can reach this class without something in this gateway having gone wrong.
 *
 * <p><b>Nothing here logs a token.</b> The refresh failure is logged with a fixed message only,
 * matching {@link SignInFailureHandler}'s rule for the same reason: Keycloak's error description
 * can quote the request that was refused. The catch-all for anything else logs the exception's
 * class name alone, never its message or its stack trace, because an arbitrary unexpected failure
 * is the one case this class cannot enumerate the safety of.
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
     * @param request carries the session to invalidate; absent when none exists, which is not an
     *     error — there is nothing left to discard
     * @param response the response to answer, since a filter later in the chain may not write to
     *     it again
     * @throws IOException if the problem body cannot be written
     */
    @ExceptionHandler(OAuth2AuthorizationException.class)
    void authorizationFailed(HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOG.warn("A relay's authorization could not be renewed; invalidating the session");
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        GatewayProblems.send(response, HttpStatus.UNAUTHORIZED, REFRESH_FAILED_BODY);
    }

    /**
     * The outbound connection to the ledger or the assistant was refused, timed out, or otherwise
     * could not be opened — {@code RestClient}'s wrapper for the underlying {@code IOException}.
     *
     * @throws IOException if the problem body cannot be written
     */
    @ExceptionHandler(ResourceAccessException.class)
    void upstreamUnavailable(HttpServletResponse response) throws IOException {
        LOG.warn("A relay's upstream service could not be reached");
        GatewayProblems.send(response, HttpStatus.BAD_GATEWAY, UPSTREAM_UNAVAILABLE_BODY);
    }

    /**
     * Anything else a relay raised, and — because {@code @ExceptionHandler(Exception.class)} has
     * no narrower reach than "this advice" — anything else any other handler in this gateway
     * raises too, unclaimed by a more specific one. That is deliberate rather than an
     * overreach: it is the same role the ledger's composition-root fallback plays, answering
     * everything nothing more specific claimed, at the lowest precedence Spring gives an
     * {@code @ExceptionHandler} match.
     *
     * <p>A framework refusal is peeled off first, at its own status and at WARN. Everything left
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
                GatewayProblems.send(response, status, refusal);
                return;
            }
        }
        LOG.error("A relay failed unexpectedly: {}", failure.getClass().getName());
        GatewayProblems.send(response, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_BODY);
    }
}
