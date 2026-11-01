package io.github.naimelijah.finassist.gateway.composition;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
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
 * @param setting the setting the URL was configured by, named in a refusal in place of the URL
 * @param base the base URL, guaranteed absolute, HTTP or HTTPS, free of a query, a fragment and a
 *     user-info, and free of a trailing slash
 */
record Origin(Setting setting, String base) {

    /**
     * The three settings an origin is configured by, each named as the owner sets it. An enum
     * rather than a string, so that a URL can never be passed where the name belongs and end up in
     * the refusal it is kept out of.
     */
    enum Setting {
        /** The origin the browser reaches FinAssist through. */
        GATEWAY_PUBLIC_URL,
        /** Keycloak as the browser sees it. */
        GATEWAY_OIDC_PUBLIC_URL,
        /** Keycloak as the gateway reaches it. */
        GATEWAY_OIDC_INTERNAL_URL
    }

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private static final String SLASH = "/";

    /**
     * @throws NullPointerException if {@code setting} or {@code base} is null
     * @throws IllegalArgumentException if the URL is not an absolute HTTP or HTTPS URL naming a
     *     host, or if it carries a query, a fragment or a user-info. A relative one would produce
     *     a redirect that resolved against whatever host the request happened to name — the one
     *     thing this type exists to prevent — so it is refused at start-up rather than silently
     *     sending the owner somewhere else. The refusal names {@code setting}, never the URL
     */
    Origin {
        Objects.requireNonNull(setting, "setting");
        base = base.strip();
        requireAbsolute(setting, base);
        while (base.endsWith(SLASH)) {
            base = base.substring(0, base.length() - SLASH.length());
        }
    }

    /**
     * Checked before the trailing slash is stripped, because stripping one from {@code http://}
     * leaves something the JDK rejects with a message about URI syntax rather than about the
     * setting the owner actually got wrong.
     */
    private static void requireAbsolute(Setting setting, String base) {
        URI parsed;
        try {
            parsed = new URI(base);
        } catch (URISyntaxException malformed) {
            throw refusal(setting, " (" + malformed.getReason() + " at index " + malformed.getIndex() + ")");
        }
        String scheme = parsed.getScheme();
        if (scheme == null || !SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) || parsed.getHost() == null) {
            throw refusal(setting, "");
        }
        if (parsed.getRawUserInfo() != null) {
            throw componentRefusal(setting, "user-info");
        }
        if (parsed.getRawQuery() != null) {
            throw componentRefusal(setting, "query");
        }
        if (parsed.getRawFragment() != null) {
            throw componentRefusal(setting, "fragment");
        }
    }

    /**
     * Never quotes the URL: the component refused can be a user-info holding a password. Names the
     * setting instead, since two of them are refused from one line at start-up.
     */
    private static IllegalArgumentException componentRefusal(Setting setting, String component) {
        return new IllegalArgumentException(setting.name() + " carries a " + component
                + ", but every URL the gateway hands out appends a path to it, so it may name only a scheme, a host,"
                + " a port and a path prefix");
    }

    /**
     * Never quotes the URL, nor attaches the JDK's exception, whose message does: a malformed URL
     * can still carry a user-info holding a password. The syntax error's reason and index are the
     * JDK's own words and a number, so they are kept to say where the URL went wrong. Names the
     * setting instead of the URL, as {@link #componentRefusal} does.
     */
    private static IllegalArgumentException refusal(Setting setting, String syntaxError) {
        return new IllegalArgumentException(setting.name() + " is not an absolute http or https URL naming a host"
                + syntaxError + ", and every URL the gateway hands out is absolute and independent of the request");
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
