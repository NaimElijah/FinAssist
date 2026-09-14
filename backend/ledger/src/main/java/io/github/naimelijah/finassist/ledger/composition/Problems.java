package io.github.naimelijah.finassist.ledger.composition;

import java.net.URI;

/**
 * The extension members and stable codes of the RFC 9457 problems the composition root answers
 * (ADR-028).
 *
 * <p>{@code title} and {@code detail} are prose for a person and may be reworded. A {@code code} is
 * what a client branches on, so once one has been served it is part of the contract and does not
 * change. Each context's own advice names its more specific codes beside its exceptions, because no
 * context may import this package.
 */
final class Problems {

    /**
     * The {@code type} of a problem that has no page of its own. RFC 9457 reads an absent {@code type}
     * as this, and Spring 7's {@code ProblemDetail} leaves it absent. It is written out anyway, so a
     * client reading the member finds it rather than having to know the RFC's default.
     */
    static final URI BLANK_TYPE = URI.create("about:blank");

    /** The extension member carrying a problem's code. */
    static final String CODE_MEMBER = "code";

    /** The request's {@code Host} header is missing, malformed or not on the allow-list (ADR-027). */
    static final String HOST_NOT_ALLOWED = "HOST_NOT_ALLOWED";

    private Problems() {}
}
