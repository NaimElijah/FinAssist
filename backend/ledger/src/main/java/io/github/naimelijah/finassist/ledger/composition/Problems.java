package io.github.naimelijah.finassist.ledger.composition;

import java.net.URI;

/**
 * The extension members and stable codes of the RFC 9457 problems the composition root answers
 * (ADR-028).
 *
 * <p>{@code title} and {@code detail} are prose for a person and may be reworded. A {@code code} is
 * what a client branches on, so once one has been served it is part of the contract and does not
 * change. Each context's advice names its own, more specific codes beside its exceptions, because no
 * context may import this package.
 */
final class Problems {

    /**
     * The {@code type} of a problem that has no page of its own. RFC 9457 reads an absent {@code type}
     * as this, and Spring 7's {@code ProblemDetail} leaves it absent. It is written out anyway, so a
     * client reading the member finds it rather than having to know the RFC's default.
     */
    static final URI BLANK_TYPE = URI.create("about:blank");

    /** RFC 9457's member identifying the problem type. */
    static final String TYPE_MEMBER = "type";

    /** RFC 9457's short, human-readable summary of the problem type. */
    static final String TITLE_MEMBER = "title";

    /** RFC 9457's copy of the HTTP status code. */
    static final String STATUS_MEMBER = "status";

    /** RFC 9457's explanation specific to this occurrence. */
    static final String DETAIL_MEMBER = "detail";

    /** RFC 9457's reference to this occurrence; Spring sets it to the request path. */
    static final String INSTANCE_MEMBER = "instance";

    /** The extension member carrying a problem's code. */
    static final String CODE_MEMBER = "code";

    /** The extension member listing what bean validation rejected, one entry per field or parameter. */
    static final String ERRORS_MEMBER = "errors";

    /** Within an {@code errors} entry: the rejected field, or the parameter when there is no field. */
    static final String FIELD_MEMBER = "field";

    /** Within an {@code errors} entry: why it was rejected. */
    static final String MESSAGE_MEMBER = "message";

    /** The request's {@code Host} header is missing, malformed or not on the allow-list (ADR-027). */
    static final String HOST_NOT_ALLOWED = "HOST_NOT_ALLOWED";

    /** Unparseable JSON, a wrong type, a missing or invalid field or parameter: fix the request. */
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";

    /** An unknown route. A context's advice names its own code for a missing resource. */
    static final String NOT_FOUND = "NOT_FOUND";

    /** The route exists and does not take this method. */
    static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    /** The route cannot produce any media type the {@code Accept} header allows. */
    static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";

    /** The body is not {@code application/json}, which every write requires (ADR-027). */
    static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    /** A conflict the framework raised, where no context's advice named a more specific code. */
    static final String CONFLICT = "CONFLICT";

    /** The request is larger than the server accepts. */
    static final String CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE";

    /** Well-formed, and refused by a rule the request broke, whether the domain's or the adapter's. */
    static final String RULE_VIOLATED = "RULE_VIOLATED";

    /** The request would combine amounts in two currencies, which the ledger never converts (ADR-012). */
    static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";

    /** The ledger failed. Nothing about the request explains it, and the detail says nothing more. */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private Problems() {}
}
