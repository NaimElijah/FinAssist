package io.github.naimelijah.finassist.ledger.composition;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses every request whose {@code Host} header does not name a host on the allow-list (ADR-027).
 *
 * <p>Binding to loopback does not keep out a hostile page in the owner's own browser. DNS rebinding
 * repoints the page's own name at {@code 127.0.0.1} after it has loaded, so the browser treats the
 * ledger as same-origin with the page and lets it read every response. What the page cannot change
 * is the name it was loaded from, which every request it makes still carries in {@code Host}.
 *
 * <p>Hosts are compared by name. The port is dropped, a bracketed IPv6 literal such as {@code
 * [::1]:8080} is unwrapped, and case is ignored. A missing, malformed or foreign header is answered
 * {@code 403} with an RFC 9457 problem whose {@code code} is {@code HOST_NOT_ALLOWED} (ADR-028). The
 * filter writes that body itself, because it runs before the dispatcher and so before any controller
 * advice could. The body never echoes the header back.
 *
 * <p>{@link LedgerConfiguration} registers it at the highest filter precedence, for every path,
 * Actuator included.
 */
public class HostAllowListFilter extends OncePerRequestFilter {

    /**
     * The part of the {@code Host} grammar the comparison needs (RFC 9110 §7.2, RFC 3986 §3.2.2): a
     * bracketed IPv6 literal, or a name containing no colon, followed by an optional all-digit port.
     * A header that does not match names nothing that could be on the list.
     */
    private static final Pattern HOST_HEADER = Pattern.compile("(?:\\[([^\\[\\]]+)]|([^:\\[\\]]+))(?::[0-9]*)?");

    private static final int IPV6_LITERAL_GROUP = 1;
    private static final int NAME_GROUP = 2;

    private static final String REFUSAL_DETAIL = "The request's Host header does not name a host this ledger serves.";

    /**
     * Serialised once, because nothing in it varies. The mix-in is the one Spring MVC's JSON converter
     * applies to a {@link ProblemDetail}, so this refusal has the same shape as every problem the
     * dispatcher answers: extension members at the top level, absent members omitted.
     */
    private static final byte[] REFUSAL = refusal();

    private final Set<String> allowedHosts;

    /**
     * Builds a filter that lets through only the named hosts.
     *
     * @param allowedHosts the host names this ledger serves, trimmed and compared case-insensitively.
     *     Blank entries are ignored. An IPv6 literal is listed without brackets, as {@code ::1}.
     * @throws IllegalArgumentException if no entry is left, because a filter that refuses every request
     *     looks like an outage rather than the configuration mistake it is
     */
    public HostAllowListFilter(Collection<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream()
                .map(String::strip)
                .filter(host -> !host.isEmpty())
                .map(HostAllowListFilter::normalised)
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("the Host allow-list names no host, so every request would be refused");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hostnameOf(request.getHeader(HttpHeaders.HOST))
                .filter(allowedHosts::contains)
                .isPresent()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(REFUSAL.length);
        response.getOutputStream().write(REFUSAL);
    }

    private static Optional<String> hostnameOf(String header) {
        return Optional.ofNullable(header)
                .map(HOST_HEADER::matcher)
                .filter(Matcher::matches)
                // Exactly one alternative matched, so exactly one group is non-null.
                .map(matcher ->
                        Objects.requireNonNullElse(matcher.group(IPV6_LITERAL_GROUP), matcher.group(NAME_GROUP)))
                .map(HostAllowListFilter::normalised);
    }

    private static String normalised(String host) {
        return host.toLowerCase(Locale.ROOT);
    }

    private static byte[] refusal() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, REFUSAL_DETAIL);
        problem.setType(Problems.BLANK_TYPE);
        problem.setProperty(Problems.CODE_MEMBER, Problems.HOST_NOT_ALLOWED);
        return JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build()
                .writeValueAsBytes(problem);
    }
}
