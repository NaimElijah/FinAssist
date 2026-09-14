package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The stable codes of the problems planning's web adapter answers, and the one way it builds them
 * (ADR-028).
 *
 * <p>The composition root's fallback keeps its codes in a package no context may import, so planning
 * names its own, exactly as accounting and budgeting do. {@code ILLEGAL_TRANSITION} is spelled as
 * accounting spells it, deliberately: a client branches on the code, whichever lifecycle refused. Once
 * served, a code is part of the contract and does not change.
 */
final class PlanningProblems {

    /** The planned flow named in the path does not exist. */
    static final String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";

    /** The planned flow's current state does not allow the requested lifecycle change. */
    static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

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
}
