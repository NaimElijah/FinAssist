package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The stable codes of the problems planning's web adapter answers, and the one way it builds them
 * (ADR-028).
 *
 * <p>The composition root's fallback keeps its codes in a package no context may import, so planning
 * names its own, exactly as accounting and budgeting do. Where a code means what another handler's
 * means, its value is spelled identically, deliberately: {@code ILLEGAL_TRANSITION} as accounting spells
 * it, {@code INTERNAL_ERROR} as the fallback does. A client branches on the code, whichever handler
 * answered. Once served, a code is part of the contract and does not change.
 */
final class PlanningProblems {

    /** The planned flow named in the path does not exist. */
    static final String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";

    /** The scenario named in the path does not exist. */
    static final String SCENARIO_NOT_FOUND = "SCENARIO_NOT_FOUND";

    /** The planned flow's current state does not allow the requested lifecycle change. */
    static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

    /** A flow named in the request body does not exist, so the request is refused rather than not found. */
    static final String UNKNOWN_PLAN = "UNKNOWN_PLAN";

    /** A scenario named in the query does not exist, so the request is refused rather than not found. */
    static final String UNKNOWN_SCENARIO = "UNKNOWN_SCENARIO";

    /** The ledger failed. Nothing about the request explains it. */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * Spelled as the fallback's, so every 500 reads the same whichever handler answered it. It says
     * nothing about the failure, by design: an exception message is among the likeliest carriers of
     * internals and data, and the log line carries the trace id that finds it (ADR-028).
     */
    private static final String INTERNAL_ERROR_DETAIL = "The ledger failed to handle this request.";

    /**
     * Written out rather than left absent, as the fallback does, so every problem has one shape on the
     * wire whichever advice answered it.
     */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    private static final String CODE_MEMBER = "code";

    private PlanningProblems() {}

    /** A problem with {@code type}, {@code title}, {@code status}, {@code detail} and {@code code}. */
    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        problem.setProperty(CODE_MEMBER, code);
        return problem;
    }

    /**
     * A 500 {@code INTERNAL_ERROR} with the generic detail. It takes no exception, so it has none to leak;
     * the handler calling it logs the exception at ERROR, because a controller-local handler answers
     * before the composition fallback that would otherwise log it.
     */
    static ProblemDetail internalError() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, INTERNAL_ERROR_DETAIL);
    }
}
