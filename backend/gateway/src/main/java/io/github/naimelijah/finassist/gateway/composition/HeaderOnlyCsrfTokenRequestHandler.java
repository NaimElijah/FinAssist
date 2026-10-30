package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * Reads a write's CSRF token from the {@code X-FINASSIST-XSRF-TOKEN} header alone, never from a
 * request parameter (ADR-030).
 *
 * <p>{@code csrf().spa()} installs a handler that reads the header and, when there is none, falls
 * back to the {@code _csrf} request parameter, from the query string or a form body. A parameter
 * needs no header to be set, so it is the one way a request that can carry no custom header could
 * still carry the token; and a parameter is relayed with the request, so the token would leave
 * this process. This handler keeps {@code spa()}'s behaviour otherwise:
 *
 * <ul>
 *   <li><b>The header is compared as it is.</b> The web client sends the cookie's raw value, so an
 *       unmasking comparison would refuse every write it makes.
 *   <li><b>The cookie is still written eagerly.</b> {@link #handle} delegates to an {@link
 *       XorCsrfTokenRequestAttributeHandler} with no attribute name of its own, exactly as {@code
 *       spa()} does: the handler then names the request attribute after the token's parameter
 *       name, and reading that name resolves the deferred token, which writes the cookie on every
 *       request rather than only where a handler asks for the token.
 * </ul>
 */
final class HeaderOnlyCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final XorCsrfTokenRequestAttributeHandler attributes = new XorCsrfTokenRequestAttributeHandler();

    HeaderOnlyCsrfTokenRequestHandler() {
        attributes.setCsrfRequestAttributeName(null);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        attributes.handle(request, response, csrfToken);
    }

    /**
     * @return the header's value, or {@code null} when the request carries no such header, which
     *     never matches the token, so {@code CsrfFilter} refuses the write
     */
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return request.getHeader(csrfToken.getHeaderName());
    }
}
