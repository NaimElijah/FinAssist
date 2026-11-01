package io.github.naimelijah.finassist.gateway.composition;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * An absolute {@code scheme://host[:port][/prefix]} base that a path is appended to, normalised
 * once so that nothing downstream has to wonder about a trailing slash. A path prefix is kept, as
 * for a Keycloak served under {@code /auth}; a query, a fragment or a user-info is refused, since
 * an appended path would land after it and a user-info would be handed out in every redirect.
 *
 * <p>Three of these exist, and keeping them apart is the whole of ADR-030's URL story: the origin
 * the browser reaches FinAssist through ({@code GATEWAY_PUBLIC_URL}), Keycloak as the browser sees
 * it ({@code GATEWAY_OIDC_PUBLIC_URL}) and Keycloak as the gateway reaches it
 * ({@code GATEWAY_OIDC_INTERNAL_URL}). Every redirect and every absolute URI the gateway hands out
 * is built from one of them and never from the incoming request, because the request arrives
 * through the web client's dev proxy and its host is whatever that proxy chose to pass on. For the
 * same reason {@code trusted-proxies} stays unset: a forwarded header says only what its sender
 * claims.
 *
 * @param base the base URL, guaranteed absolute, HTTP or HTTPS, free of a query, a fragment and a
 *     user-info, and free of a trailing slash
 */
record Origin(String base) {

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private static final String SLASH = "/";

    /**
     * @throws IllegalArgumentException if the URL is not an absolute HTTP or HTTPS URL naming a
     *     host, or if it carries a query, a fragment or a user-info. A relative one would produce
     *     a redirect that resolved against whatever host the request happened to name — the one
     *     thing this type exists to prevent — so it is refused at start-up rather than silently
     *     sending the owner somewhere else
     */
    Origin {
        base = base.strip();
        requireAbsolute(base);
        while (base.endsWith(SLASH)) {
            base = base.substring(0, base.length() - SLASH.length());
        }
    }

    /**
     * Checked before the trailing slash is stripped, because stripping one from {@code http://}
     * leaves something the JDK rejects with a message about URI syntax rather than about the
     * setting the owner actually got wrong.
     */
    private static void requireAbsolute(String base) {
        URI parsed;
        try {
            parsed = URI.create(base);
        } catch (IllegalArgumentException malformed) {
            throw refusal(base, malformed);
        }
        String scheme = parsed.getScheme();
        if (scheme == null || !SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) || parsed.getHost() == null) {
            throw refusal(base, null);
        }
        if (parsed.getRawUserInfo() != null) {
            throw componentRefusal("user-info");
        }
        if (parsed.getRawQuery() != null) {
            throw componentRefusal("query");
        }
        if (parsed.getRawFragment() != null) {
            throw componentRefusal("fragment");
        }
    }

    /** Never quotes the URL: the component refused can be a user-info holding a password. */
    private static IllegalArgumentException componentRefusal(String component) {
        return new IllegalArgumentException("A gateway origin URL carries a " + component
                + ", but every URL the gateway hands out appends a path to it, so it may name only a scheme, a host,"
                + " a port and a path prefix");
    }

    private static IllegalArgumentException refusal(String base, Throwable cause) {
        return new IllegalArgumentException(
                "'" + base + "' is not an absolute http or https URL naming a host, and every URL the gateway"
                        + " hands out is absolute and independent of the request",
                cause);
    }

    /** The origin's own root, with the trailing slash a browser lands on. */
    String root() {
        return base + SLASH;
    }

    /**
     * @param path an absolute path beginning with {@code /}
     * @return that path made absolute against this origin
     */
    String path(String path) {
        return base + path;
    }
}
