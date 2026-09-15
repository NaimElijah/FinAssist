package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps budgeting's typed exceptions to RFC 9457 problems with specific codes (ADR-028).
 *
 * <p>Scoped to this package's controllers, and ordered first, for the reason {@code
 * AccountingProblemAdvice} gives: Spring MVC uses the first advice with a matching handler, and the
 * composition fallback handles {@link Exception}, so without the explicit order this mapping could
 * lose to it. Whatever this does not map still reaches the fallback: an {@link
 * IllegalArgumentException} becomes 422 {@code RULE_VIOLATED}, and anything unexpected a logged 500.
 *
 * <p>The detail is the exception's message. This is a 4xx refusal whose message names the budget's id,
 * and the ledger holds no secrets (ADR-019).
 */
@RestControllerAdvice(basePackageClasses = BudgetController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BudgetingProblemAdvice {

    @ExceptionHandler(BudgetNotFoundException.class)
    ProblemDetail budgetNotFound(BudgetNotFoundException exception) {
        return BudgetingProblems.problem(
                HttpStatus.NOT_FOUND, BudgetingProblems.BUDGET_NOT_FOUND, exception.getMessage());
    }
}
