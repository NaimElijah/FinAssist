package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.EntryCurrencyMismatchException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransactionNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps accounting's typed exceptions to RFC 9457 problems with specific codes (ADR-028).
 *
 * <p>Scoped to this package's controllers, and ordered first. Spring MVC asks advices in order and
 * uses the first with a matching handler, and the composition fallback handles {@link Exception}, so
 * without the explicit order these mappings could lose to it. Whatever this does not map still reaches
 * the fallback: an {@link IllegalArgumentException} becomes 422 {@code RULE_VIOLATED}, and anything
 * unexpected a logged 500.
 *
 * <p>Each detail is the exception's message. These are 4xx refusals whose messages describe the request
 * and the books it touched, such as an account's name, and the ledger holds no secrets (ADR-019).
 *
 * <p>An {@link AccountNotFoundException} is 404 here because an account id in the path names the
 * resource the request is about. Where the id comes from a body instead, the controller handles the
 * exception itself and answers 422, which takes precedence over this advice.
 */
@RestControllerAdvice(basePackageClasses = AccountController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountingProblemAdvice {

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException exception) {
        return AccountingProblems.problem(
                HttpStatus.NOT_FOUND, AccountingProblems.ACCOUNT_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail transactionNotFound(TransactionNotFoundException exception) {
        return AccountingProblems.problem(
                HttpStatus.NOT_FOUND, AccountingProblems.TRANSACTION_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(DuplicateAccountNameException.class)
    ProblemDetail duplicateAccountName(DuplicateAccountNameException exception) {
        return AccountingProblems.problem(
                HttpStatus.CONFLICT, AccountingProblems.DUPLICATE_ACCOUNT_NAME, exception.getMessage());
    }

    /** A conflict with current state rather than a malformed request: the same call was legal earlier. */
    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegalTransition(IllegalTransitionException exception) {
        return AccountingProblems.problem(
                HttpStatus.CONFLICT, AccountingProblems.ILLEGAL_TRANSITION, exception.getMessage());
    }

    @ExceptionHandler(EntryCurrencyMismatchException.class)
    ProblemDetail entryCurrencyMismatch(EntryCurrencyMismatchException exception) {
        return AccountingProblems.problem(
                HttpStatus.UNPROCESSABLE_CONTENT, AccountingProblems.CURRENCY_MISMATCH, exception.getMessage());
    }
}
