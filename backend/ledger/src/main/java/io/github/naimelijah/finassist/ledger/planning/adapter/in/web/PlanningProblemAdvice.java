package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.IllegalPlanTransitionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps planning's typed exceptions to RFC 9457 problems with specific codes (ADR-028).
 *
 * <p>Scoped to this package's controllers, and ordered first, for the reason {@code
 * AccountingProblemAdvice} gives: Spring MVC uses the first advice with a matching handler, and the
 * composition fallback handles {@link Exception}, so without the explicit order these mappings could lose
 * to it. Whatever this does not map still reaches the fallback: an {@link IllegalArgumentException}
 * becomes 422 {@code RULE_VIOLATED}, and anything unexpected a logged 500.
 *
 * <p>Each detail is the exception's message. These are 4xx refusals whose messages name a plan's id or
 * state, and the ledger holds no secrets (ADR-019).
 *
 * <p>A {@link PlanNotFoundException} is 404 here because a plan id in the path names the resource the
 * request is about. Where a plan id comes from somewhere else the same exception means something else,
 * and a controller handles it itself, which takes precedence over this advice.
 */
@RestControllerAdvice(basePackageClasses = PlannedFlowController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlanningProblemAdvice {

    @ExceptionHandler(PlanNotFoundException.class)
    ProblemDetail planNotFound(PlanNotFoundException exception) {
        return PlanningProblems.problem(HttpStatus.NOT_FOUND, PlanningProblems.PLAN_NOT_FOUND, exception.getMessage());
    }

    /** A conflict with current state rather than a malformed request: the same call was legal earlier. */
    @ExceptionHandler(IllegalPlanTransitionException.class)
    ProblemDetail illegalPlanTransition(IllegalPlanTransitionException exception) {
        return PlanningProblems.problem(
                HttpStatus.CONFLICT, PlanningProblems.ILLEGAL_TRANSITION, exception.getMessage());
    }
}
