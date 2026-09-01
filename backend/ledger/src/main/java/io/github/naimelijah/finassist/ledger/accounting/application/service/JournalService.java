package io.github.naimelijah.finassist.ledger.accounting.application.service;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.JournalLine;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewBalanceUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewJournalUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the books. Writes nothing, stores nothing, caches nothing.
 *
 * <p>Both answers are derived on every call. That is the whole point of "derived, never stored": a
 * balance cannot drift from its entries when there is no second copy of it to drift.
 */
public class JournalService implements ViewBalanceUseCase, ViewJournalUseCase {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public JournalService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Balance balanceOf(AccountId account) {
        Objects.requireNonNull(account, "account");
        // Loaded for its currency, which is the answer when nothing has touched it.
        Account held = require(account);
        return Balance.of(account, held.currency(), transactions.findByAccount(account));
    }

    @Override
    public List<JournalLine> postedBetween(LocalDate fromInclusive, LocalDate toExclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");

        List<JournalLine> lines = new ArrayList<>();
        for (Transaction transaction : transactions.findBetween(fromInclusive, toExclusive)) {
            if (!transaction.countsTowardsBalance()) {
                continue;
            }
            for (Entry entry : transaction.entries()) {
                Account account = require(entry.account());
                lines.add(new JournalLine(
                        transaction.id(),
                        transaction.occurredOn(),
                        transaction.description(),
                        account.id(),
                        account.type(),
                        entry.amount()));
            }
        }
        return List.copyOf(lines);
    }

    private Account require(AccountId id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
