package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The stable codes of the problems budgeting's web adapter answers, and the one way it builds them
 * (ADR-028).
 *
 * <p>The composition root's fallback keeps its codes in a package no context may import, so budgeting
 * names its own, exactly as accounting does. Once served, a code is part of the contract and does not
 * change.
 */
final class BudgetingProblems {

    /** The budget named in the path does not exist. */
    static final String BUDGET_NOT_FOUND = "BUDGET_NOT_FOUND";

    /**
     * Written out rather than left absent, as the fallback does, so every problem has one shape on the
     * wire whichever advice answered it.
     */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    private static final String CODE_MEMBER = "code";

    private BudgetingProblems() {}

    /** A problem with {@code type}, {@code title}, {@code status}, {@code detail} and {@code code}. */
    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        problem.setProperty(CODE_MEMBER, code);
        return problem;
    }
}
