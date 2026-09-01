package io.github.naimelijah.finassist.ledger.accounting.application.service;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import java.util.Objects;

/**
 * Opens accounts.
 *
 * <p>Orchestration only: it holds the one rule the domain cannot, which is that a name is unique
 * across the chart of accounts. Everything else about an account — the blank name, the single
 * currency, the absence of a balance field — is {@link Account}'s own business.
 *
 * <p>No annotation, and no framework import. It is constructed by
 * {@code io.github.naimelijah.finassist.ledger.composition.LedgerConfiguration}, which is the only
 * package that knows Spring exists. See ADR-021 and ADR-022.
 */
public class AccountService implements OpenAccountUseCase {

    private final AccountRepository accounts;
    private final AccountingEvents events;

    public AccountService(AccountRepository accounts, AccountingEvents events) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public Account open(OpenAccountCommand command) {
        Objects.requireNonNull(command, "command");

        // The command has already normalised the name, so this looks for the form that would be
        // stored rather than the form that was typed.
        if (accounts.findByName(command.name()).isPresent()) {
            throw new DuplicateAccountNameException(command.name());
        }

        Account account = Account.open(command.name(), command.type(), command.currency());
        accounts.save(account);
        events.publish(
                new AccountingEvent.AccountOpened(account.id(), account.name(), account.type(), account.currency()));
        return account;
    }
}
