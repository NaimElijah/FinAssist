package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway's stable RFC 9457 problem codes, and the writer that serialises one in the ledger's
 * shape (ADR-028, extended by ADR-030).
 *
 * <p>Every code the gateway answers is declared here, in one place: {@link HostAllowListFilter}
 * answers {@code HOST_NOT_ALLOWED}; {@link ProblemRequestRejectedHandler} answers {@code
 * MALFORMED_REQUEST} for a URL Spring Security's firewall refuses; {@link RequestBodyLimitFilter}
 * answers {@code CONTENT_TOO_LARGE} for a body over its limit; {@code
 * GatewaySecurityConfiguration}'s entry point answers
 * {@code UNAUTHENTICATED}, and its access-denied handler answers both {@code FORBIDDEN} and {@code
 * CSRF_TOKEN_INVALID}, branching on whether the denial was a CSRF failure; {@link
 * LoginEntryNavigationFilter} answers {@code FORBIDDEN} too, with a detail of its own, for a
 * browser request to the login entry that is not a top-level navigation. The sign-in failure
 * handler answers neither, since a failed sign-in redirects the browser rather than answering a
 * problem, while {@link LoginEntryFailureHandler}, which refuses a login entry naming a
 * registration that does not exist, answers {@code UNAUTHENTICATED} through that same entry point
 * and {@code INTERNAL_ERROR} for anything else that stops a sign-in being started; and {@code
 * GatewayRelayProblems} answers {@code UNAUTHENTICATED} for a refused refresh,
 * {@code UPSTREAM_UNAVAILABLE} and {@code INTERNAL_ERROR} for a relay's other failures, and
 * {@code METHOD_NOT_ALLOWED} or {@code NOT_ACCEPTABLE} for the two refusals Spring MVC itself can
 * raise here. Each is spelled exactly as the ledger spells the same code, so a client branching on
 * one reads either service.
 * Keeping the whole vocabulary in one class, rather than a constant beside each caller, is what
 * stops a second name being invented for a code already fixed.
 *
 * <p>{@code title} and {@code detail} are prose for a person and may be reworded; {@code code} is
 * what a client branches on and does not change once served.
 *
 * <p>The writer lives here rather than inside {@link HostAllowListFilter} alone because the
 * gateway has no dispatcher-level problem handler for this failure to reach: a {@code
 * @RestControllerAdvice} only sees a request that reached a controller, and a filter or a
 * security entry point that refuses a request earlier has to write its own body, exactly as the
 * ledger's own {@code HostAllowListFilter} does. One writer, reused by every filter, entry point
 * and access-denied handler, is what keeps their bodies in one shape.
 */
final class GatewayProblems {

    /**
     * The {@code type} of a problem that has no page of its own. RFC 9457 reads an absent
     * {@code type} as this, and Spring's {@code ProblemDetail} leaves it absent; it is written
     * out anyway, so a client reading the member finds it rather than having to know the RFC's
     * default.
     */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    /** The extension member carrying a problem's code. */
    private static final String CODE_MEMBER = "code";

    /** The request's {@code Host} header is missing, malformed or not on the allow-list (ADR-030). */
    static final String HOST_NOT_ALLOWED = "HOST_NOT_ALLOWED";

    /** The request's body is larger than {@link RequestBodyLimitFilter#MAX_REQUEST_BODY_BYTES}. */
    static final String CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE";

    /** The request's URL is one the firewall refuses to route, before any filter of the chain runs. */
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";

    /** No session and no bearer token accepted here either — the gateway grants nothing on its own. */
    static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /**
     * A refusal no credential would change: a session that exists but may not make this request,
     * or a browser request to the public login entry that is not a top-level navigation.
     */
    static final String FORBIDDEN = "FORBIDDEN";

    /** A write arrived with a missing or wrong CSRF token. */
    static final String CSRF_TOKEN_INVALID = "CSRF_TOKEN_INVALID";

    /** A relayed request's upstream service — the ledger or the assistant — could not be reached. */
    static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";

    /** The path exists here, but no handler answers this method. */
    static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    /** The handler produces nothing the request's {@code Accept} header would take. */
    static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";

    /** The gateway failed. Nothing about the request explains it, and the detail says nothing more. */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * Built once, because nothing about the serialisation varies. The mix-in is the one Spring
     * MVC's own JSON converter applies to a {@link ProblemDetail}, so a body this class writes
     * has the same shape as every problem the dispatcher would have answered itself.
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private GatewayProblems() {}

    /**
     * Serialises a problem with the given status, detail and code as RFC 9457 problem+json bytes.
     *
     * @param status the HTTP status the problem answers with
     * @param detail prose for a person; the caller's responsibility to keep free of a secret, a
     *     token or a cookie (ADR-030)
     * @param code the stable code a client branches on, one of this class's constants
     * @return the serialised body, ready to write with {@code content-type: application/problem+json}
     */
    static byte[] write(HttpStatus status, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        problem.setProperty(CODE_MEMBER, code);
        return JSON.writeValueAsBytes(problem);
    }

    /**
     * Writes an already serialised problem as the whole response.
     *
     * <p>Every caller refuses a request before the dispatcher, so each has to set the status, the
     * content type and the length itself; doing it here rather than at each of them is what stops
     * one of them forgetting the {@code application/problem+json} content type and answering a
     * body a client would read as something else.
     *
     * @param response the response to write, which no filter later in the chain may then write to
     * @param status the HTTP status, which must be the one {@code body} was built with
     * @param body the bytes from {@link #write(HttpStatus, String, String)}
     * @throws IOException if the response cannot be written
     */
    static void send(HttpServletResponse response, HttpStatus status, byte[] body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
