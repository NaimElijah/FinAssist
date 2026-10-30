package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a sign-in start only from a top-level navigation, refusing any other browser request to the
 * login entry with {@code 403 FORBIDDEN} before a session is created for it (ADR-030).
 *
 * <p>The login entry is public, and each request it accepts without a session creates one to hold
 * the authorization request. The web client reaches it only by a link the browser follows in its
 * own window, which the browser marks {@code Sec-Fetch-Mode: navigate} and {@code Sec-Fetch-Dest:
 * document}. Any other browser request is refused:
 *
 * <ul>
 *   <li>a {@code Sec-Fetch-Mode} other than {@code navigate}: a request for an image, a script or a
 *       {@code fetch}, none of which could follow a sign-in;
 *   <li>a {@code Sec-Fetch-Dest} other than {@code document}: a navigation into an {@code iframe},
 *       an {@code object} or an {@code embed}, which is a navigation, but not of the window.
 * </ul>
 *
 * <p>Each header is checked only when it is present: only a browser sends Fetch Metadata, and a
 * client that does not is not the page this guards against. {@link
 * PreSignInAuthorizationRequestRepository} bounds the sessions such requests create. Every other
 * path passes untouched.
 *
 * <p>The refusal is {@code 403 FORBIDDEN}, not the entry point's {@code 401}: the login entry is
 * public, so no credential would change the answer. It carries a fixed detail of its own, written
 * through {@link GatewayProblems}, because {@link ProblemAccessDeniedHandler}'s speaks of a
 * session, and none exists yet. Nothing is logged, for the entry point's own reason: the refusal
 * carries nothing diagnostic, and a line per request would be log-filling a page could provoke.
 * It must run ahead of {@code OAuth2AuthorizationRequestRedirectFilter}, which creates the
 * session.
 */
final class LoginEntryNavigationFilter extends OncePerRequestFilter {

    /** The Fetch Metadata header a browser sends to say what kind of request it is making. */
    static final String SEC_FETCH_MODE = "Sec-Fetch-Mode";

    /** The {@link #SEC_FETCH_MODE} of a navigation, the only mode that may start a sign-in. */
    static final String NAVIGATE = "navigate";

    /** The Fetch Metadata header a browser sends to say where the answer will be used. */
    static final String SEC_FETCH_DEST = "Sec-Fetch-Dest";

    /** The {@link #SEC_FETCH_DEST} of the browser's own window, the only one a sign-in may open in. */
    static final String DOCUMENT = "document";

    private static final String NOT_A_TOP_LEVEL_NAVIGATION = "A sign-in starts only from a top-level navigation.";

    private final RequestMatcher loginEntry = PathPatternRequestMatcher.pathPattern(BffRoutes.LOGIN_ENTRY_PATTERN);

    /** Serialised once: nothing in it varies by request. */
    private static final byte[] REFUSAL =
            GatewayProblems.write(HttpStatus.FORBIDDEN, NOT_A_TOP_LEVEL_NAVIGATION, GatewayProblems.FORBIDDEN);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (loginEntry.matches(request) && !isTopLevelNavigation(request)) {
            GatewayProblems.send(response, HttpStatus.FORBIDDEN, REFUSAL);
            return;
        }
        chain.doFilter(request, response);
    }

    /** False only when a Fetch Metadata header the browser sent names something else. */
    private static boolean isTopLevelNavigation(HttpServletRequest request) {
        return isAbsentOrEqualTo(request.getHeader(SEC_FETCH_MODE), NAVIGATE)
                && isAbsentOrEqualTo(request.getHeader(SEC_FETCH_DEST), DOCUMENT);
    }

    private static boolean isAbsentOrEqualTo(String headerValue, String expected) {
        return headerValue == null || expected.equals(headerValue);
    }
}
