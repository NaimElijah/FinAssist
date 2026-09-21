package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransitionTransactionUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewTransactionsUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The journal over HTTP: record a transaction, list a period's transactions, and move one through its
 * lifecycle (ADR-028).
 *
 * <p>It calls in-ports only and is component-scanned, for the reasons {@link AccountController} gives.
 * A record answers 201 with the transaction and no {@code Location} header. A lifecycle change is a
 * {@code POST} to a sub-resource carrying its date in the body, because no server reads a clock, and
 * answers the updated transaction. Every write accepts {@code application/json} only (ADR-030).
 *
 * <p>{@link AccountingProblemAdvice} maps the lifecycle's failures: an unknown transaction in the path is
 * 404 {@code TRANSACTION_NOT_FOUND}, and a change the current state does not allow is 409 {@code
 * ILLEGAL_TRANSITION}. A date before the one it must follow is the domain's {@link
 * IllegalArgumentException}, which the composition fallback answers 422 {@code RULE_VIOLATED}.
 *
 * <p>An account named in a body is not the resource the request is about, so an unknown one is 422
 * {@code UNKNOWN_ACCOUNT}, not the 404 the advice gives an account named in a path (ADR-028). That
 * mapping is a handler on this controller, which Spring MVC consults before any advice.
 *
 * <p>Handler names are unique across the ledger, not only within this class. springdoc makes each one
 * an {@code operationId}, and a second context's {@code listTransactions} would be renamed {@code
 * listTransactions_1} in whatever order the controllers were scanned.
 */
@RestController
@RequestMapping(path = "/api/v1/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransactionController {

    private final RecordTransactionUseCase recorder;
    private final ViewTransactionsUseCase journal;
    private final TransitionTransactionUseCase lifecycle;

    /** Takes each in-port by type; each has exactly one implementing bean (ADR-028). */
    public TransactionController(
            RecordTransactionUseCase recorder,
            ViewTransactionsUseCase journal,
            TransitionTransactionUseCase lifecycle) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * Records a transaction, which starts pending. Entries that do not balance are 422 {@code
     * RULE_VIOLATED}, and an entry in a currency its account does not hold is 422 {@code CURRENCY_MISMATCH}.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse recordTransaction(@Valid @RequestBody RecordTransactionRequest request) {
        return TransactionResponse.from(recorder.record(request.toCommand()));
    }

    /**
     * Every transaction that occurred from {@code from} up to but not including {@code to}, voided ones
     * included, in the order the port guarantees.
     *
     * <p>A period that does not start before it ends is well-formed and refused, so it is thrown as the
     * ledger's rule signal and answered 422 {@code RULE_VIOLATED}, not 400.
     */
    @GetMapping
    public List<TransactionResponse> listTransactions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "A period must start before it ends, but from " + from + " is not before to " + to);
        }
        return journal.between(from, to).stream().map(TransactionResponse::from).toList();
    }

    /** Clears a pending transaction on the date given. */
    @PostMapping(path = "/{id}/clear", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionResponse clearTransaction(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return TransactionResponse.from(lifecycle.clear(new TransactionId(id), request.on()));
    }

    /** Reconciles a cleared transaction on the date given. */
    @PostMapping(path = "/{id}/reconcile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionResponse reconcileTransaction(
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return TransactionResponse.from(lifecycle.reconcile(new TransactionId(id), request.on()));
    }

    /** Voids a pending or cleared transaction on the date given, for the reason given. */
    @PostMapping(path = "/{id}/void", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionResponse voidTransaction(@PathVariable UUID id, @Valid @RequestBody VoidRequest request) {
        return TransactionResponse.from(lifecycle.markVoided(new TransactionId(id), request.on(), request.reason()));
    }

    /** An entry names an account that does not exist: the request is well-formed and refused (ADR-028). */
    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail unknownAccount(AccountNotFoundException exception) {
        return AccountingProblems.problem(
                HttpStatus.UNPROCESSABLE_CONTENT, AccountingProblems.UNKNOWN_ACCOUNT, exception.getMessage());
    }
}
