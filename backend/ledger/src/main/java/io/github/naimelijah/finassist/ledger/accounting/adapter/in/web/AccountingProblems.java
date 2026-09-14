package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The stable codes of the problems accounting's web adapter answers, and the one way it builds them
 * (ADR-028).
 *
 * <p>The composition root's fallback keeps its codes in a package no context may import, so accounting
 * names its own. Where two codes mean the same thing their values are spelled identically, and that
 * duplication across the boundary is deliberate: {@code CURRENCY_MISMATCH} is what a client branches
 * on, whichever side raised it. Once served, a code is part of the contract and does not change.
 */
final class AccountingProblems {

    /** The account named in the path does not exist. */
    static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";

    /** The transaction named in the path does not exist. */
    static final String TRANSACTION_NOT_FOUND = "TRANSACTION_NOT_FOUND";

    /** An account of that name is already open; names are unique in the chart of accounts. */
    static final String DUPLICATE_ACCOUNT_NAME = "DUPLICATE_ACCOUNT_NAME";

    /** The transaction's current state does not allow the requested lifecycle change. */
    static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

    /** An entry is in a currency its account does not hold, and the ledger never converts (ADR-012). */
    static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";

    /**
     * Written out rather than left absent, as the fallback does, so every problem has one shape on the
     * wire whichever advice answered it.
     */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    private static final String CODE_MEMBER = "code";

    private AccountingProblems() {}

    /** A problem with {@code type}, {@code title}, {@code status}, {@code detail} and {@code code}. */
    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        problem.setProperty(CODE_MEMBER, code);
        return problem;
    }
}
