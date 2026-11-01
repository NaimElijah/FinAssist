package io.github.naimelijah.finassist.ledger.composition;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a genuinely identified caller who may not do this with {@code 403} {@code FORBIDDEN};
 * an anonymous one is handed to {@link ProblemAuthenticationEntryPoint} instead, as {@code 401}
 * (ADR-030).
 *
 * <p>{@code AuthorizationFilter} raises the same {@link AccessDeniedException} for both cases:
 * a real principal denied a permission, and an anonymous request denied because {@code
 * denyAll}/{@code hasAuthority} never admits an unauthenticated caller either. {@code
 * ExceptionTranslationFilter} does not tell the two apart on its own here, so this handler does:
 * it reads the {@link SecurityContextHolder}'s own {@link Authentication} and, when it is absent
 * or anonymous, delegates to the entry point rather than answering 403 for a caller who was never
 * given the chance to authenticate at all.
 *
 * <p>The 403 carries {@code WWW-Authenticate: Bearer error="insufficient_scope"} and the
 * {@code FORBIDDEN} code, with a fixed detail that names neither the subject nor the permission.
 *
 * <p>Not logged, for the genuine-denial branch. Unlike a rejected token, an authenticated caller
 * asking for something their role does not grant is an ordinary, expected shape of request — a
 * viewer's browser calling a write endpoint the UI should not have offered it, say — and logging
 * one per such request would be noise that also risked a line naming a subject and the permission
 * it lacked. The anonymous branch is logged by the entry point it delegates to, exactly as any
 * other missing-token refusal is.
 */
final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final String DETAIL = "This token does not grant the permission this request needs.";
    private static final String MISSING_AUTHENTICATION_MESSAGE =
            "Full authentication is required to access this resource";

    private static final byte[] BODY = problem();

    private final AuthenticationEntryPoint authenticationEntryPoint;

    ProblemAccessDeniedHandler(AuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new InsufficientAuthenticationException(MISSING_AUTHENTICATION_MESSAGE, accessDeniedException));
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
    }

    private static byte[] problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, DETAIL);
        problem.setType(Problems.BLANK_TYPE);
        problem.setProperty(Problems.CODE_MEMBER, Problems.FORBIDDEN);
        return JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build()
                .writeValueAsBytes(problem);
    }
}
