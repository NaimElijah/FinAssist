package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ListAccountsUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewBalanceUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewTransactionsUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The chart of accounts over HTTP: list the accounts, open one, and read an account's balance and its
 * transactions (ADR-028).
 *
 * <p>It calls in-ports only. {@code LedgerArchitectureTest.web_adapters_reach_no_outbound_port} fails
 * the build if it reaches a repository or an outbound adapter. It is component-scanned rather than
 * built in {@code LedgerConfiguration}, the one bounded exception ADR-028 makes to hand-built wiring,
 * so that a {@code @WebMvcTest} slice loads it.
 *
 * <p>An open answers 201 with the account and no {@code Location} header, because there is no
 * single-item read for one to name. Writes accept {@code application/json} only, so a form post from a
 * cross-site page is refused with 415 before this runs (ADR-027). {@link AccountingProblemAdvice} maps
 * the failures.
 */
@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountController {

    private final ListAccountsUseCase chart;
    private final OpenAccountUseCase opener;
    private final ViewBalanceUseCase balances;
    private final ViewTransactionsUseCase journal;

    /** Takes each in-port by type; each has exactly one implementing bean (ADR-028). */
    public AccountController(
            ListAccountsUseCase chart,
            OpenAccountUseCase opener,
            ViewBalanceUseCase balances,
            ViewTransactionsUseCase journal) {
        this.chart = Objects.requireNonNull(chart, "chart");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /*
     * Handler names are unique across the ledger on purpose: springdoc makes each one an operationId, and
     * a second context's "all" would be renamed "all_1" in whatever order the controllers were scanned.
     */

    /** Every account, in the name order the port guarantees. */
    @GetMapping
    public List<AccountResponse> listAccounts() {
        return chart.all().stream().map(AccountResponse::from).toList();
    }

    /** Opens an account. A name already open is 409 {@code DUPLICATE_ACCOUNT_NAME}. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse openAccount(@Valid @RequestBody OpenAccountRequest request) {
        return AccountResponse.from(opener.open(request.toCommand()));
    }

    /** The account's balance, derived from its entries. An unknown account is 404 {@code ACCOUNT_NOT_FOUND}. */
    @GetMapping("/{id}/balance")
    public BalanceResponse viewBalance(@PathVariable UUID id) {
        return BalanceResponse.from(balances.balanceOf(new AccountId(id)));
    }

    /**
     * Every transaction with an entry against the account, voided ones included, in the order the port
     * guarantees. An unknown account is 404 {@code ACCOUNT_NOT_FOUND}.
     */
    @GetMapping("/{id}/transactions")
    public List<TransactionResponse> listAccountTransactions(@PathVariable UUID id) {
        return journal.forAccount(new AccountId(id)).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
