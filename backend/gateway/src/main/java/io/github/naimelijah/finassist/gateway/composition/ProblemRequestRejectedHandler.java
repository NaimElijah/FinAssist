package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * Answers a request Spring Security's firewall refuses with {@code 400 MALFORMED_REQUEST}, in the
 * gateway's own problem shape (ADR-028, ADR-030).
 *
 * <p>{@code StrictHttpFirewall} refuses a URL it cannot route safely — an encoded dot segment or
 * percent sign, an empty segment, a path parameter — before any filter of the chain runs. Spring's
 * default handler answers with {@code sendError(400)}, and the container's error dispatch then
 * renders Boot's own body, which carries no {@code code} and echoes the caller's path. The ledger
 * answers a malformed request {@code MALFORMED_REQUEST}, so the gateway answers this one the same.
 *
 * <p>The body is fixed and names nothing from the request. Nothing is logged: the refusal carries
 * nothing diagnostic, and a line per request would be log-filling any caller could provoke.
 */
final class ProblemRequestRejectedHandler implements RequestRejectedHandler {

    private static final String DETAIL = "The request's URL is not one the gateway accepts.";

    /** Serialised once: nothing in it varies by request. */
    private static final byte[] BODY =
            GatewayProblems.write(HttpStatus.BAD_REQUEST, DETAIL, GatewayProblems.MALFORMED_REQUEST);

    /**
     * @param request the refused request
     * @param response the response to write, which nothing later may write to
     * @param rejection why the firewall refused it; never written to the body or the log
     * @throws IOException if the problem body cannot be written
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException rejection)
            throws IOException {
        GatewayProblems.send(response, HttpStatus.BAD_REQUEST, BODY);
    }
}
