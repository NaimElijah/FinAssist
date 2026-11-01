package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Answers a refusal that is not about identity with {@code 403} and an RFC 9457 problem, telling a
 * missing or wrong CSRF token apart from every other denial (ADR-030).
 *
 * <p>Two codes, because a client can do something about one of them and nothing about the other:
 * {@code CSRF_TOKEN_INVALID} means "read the {@code FINASSIST-XSRF-TOKEN} cookie and send it back
 * in the {@code X-FINASSIST-XSRF-TOKEN} header", while {@code FORBIDDEN} means the session exists
 * and still may not do this, a refusal no credential would change. {@code CsrfFilter} hands its
 * {@code MissingCsrfTokenException} and {@code InvalidCsrfTokenException} — both {@link
 * CsrfException} — straight to this handler rather than throwing them up the chain, so both arrive
 * here and the distinction can be made by type.
 *
 * <p>An <em>unauthenticated</em> request is not sent here for an authorization denial: {@code
 * ExceptionTranslationFilter} sends an anonymous principal to {@link
 * ProblemAuthenticationEntryPoint} instead, which is why a denied path answers {@code 401} before
 * sign-in and {@code 403} after it. A CSRF failure is the exception: {@code CsrfFilter} runs before
 * any authorization decision and calls this handler itself, so a write that lacks the token gets
 * {@code CSRF_TOKEN_INVALID} signed in or not. {@link LoginEntryNavigationFilter} answers the same {@code
 * FORBIDDEN} code without coming here, with a detail of its own, because it refuses a request to
 * the public login entry before any session exists.
 *
 * <p>Neither body names the path, the token or the session. Nothing is logged: a client that
 * forgot the header will retry with it, and the gateway holds a token for every signed-in user, so
 * a line about a token is a line that could one day carry one.
 */
final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final String CSRF_DETAIL = "This write needs the CSRF token from the "
            + GatewaySecurityConfiguration.CSRF_COOKIE_NAME + " cookie, sent back in the "
            + GatewaySecurityConfiguration.CSRF_HEADER_NAME + " header.";

    private static final String FORBIDDEN_DETAIL = "This session may not make this request.";

    private static final byte[] CSRF_BODY =
            GatewayProblems.write(HttpStatus.FORBIDDEN, CSRF_DETAIL, GatewayProblems.CSRF_TOKEN_INVALID);

    private static final byte[] FORBIDDEN_BODY =
            GatewayProblems.write(HttpStatus.FORBIDDEN, FORBIDDEN_DETAIL, GatewayProblems.FORBIDDEN);

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        byte[] body = (accessDeniedException instanceof CsrfException) ? CSRF_BODY : FORBIDDEN_BODY;
        GatewayProblems.send(response, HttpStatus.FORBIDDEN, body);
    }
}
