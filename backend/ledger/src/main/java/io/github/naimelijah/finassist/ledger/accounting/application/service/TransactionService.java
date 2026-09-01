package io.github.naimelijah.finassist.ledger.accounting.application.service;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.EntryCurrencyMismatchException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransactionNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransitionTransactionUseCase;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records transactions and moves them through their lifecycle.
 *
 * <p>This is where the requirement that an entry match its account's currency finally lands. It could
 * never be a domain rule: an {@code Entry} holds an {@code AccountId}, so checking it needs something
 * that can turn one into an {@code Account}. That is a repository, and a repository is this layer.
 *
 * <p>The balancing rule stays in {@link Transaction}, where it belongs — this service does not
 * re-check it, and constructing the transaction is what enforces it.
 */
public class TransactionService implements RecordTransactionUseCase, TransitionTransactionUseCase {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final AccountingEvents events;

    public TransactionService(AccountRepository accounts, TransactionRepository transactions, AccountingEvents events) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public Transaction record(RecordTransactionCommand command) {
        Objects.requireNonNull(command, "command");

        List<AccountId> touched = new ArrayList<>();
        for (Entry entry : command.entries()) {
            Account account = require(entry.account());
            if (entry.amount().currency() != account.currency()) {
                throw new EntryCurrencyMismatchException(account, entry.amount().currency());
            }
            touched.add(account.id());
        }

        Transaction transaction = Transaction.record(command.occurredOn(), command.description(), command.entries());
        transactions.save(transaction);
        events.publish(new AccountingEvent.TransactionRecorded(
                transaction.id(), transaction.occurredOn(), transaction.description(), touched));
        return transaction;
    }

    @Override
    public Transaction clear(TransactionId id, LocalDate clearedOn) {
        Transaction cleared = require(id).clear(clearedOn);
        transactions.save(cleared);
        events.publish(new AccountingEvent.TransactionCleared(cleared.id(), clearedOn));
        return cleared;
    }

    @Override
    public Transaction reconcile(TransactionId id, LocalDate reconciledOn) {
        Transaction reconciled = require(id).reconcile(reconciledOn);
        transactions.save(reconciled);
        events.publish(new AccountingEvent.TransactionReconciled(reconciled.id(), reconciledOn));
        return reconciled;
    }

    @Override
    public Transaction markVoided(TransactionId id, LocalDate voidedOn, String reason) {
        Transaction voided = require(id).markVoided(voidedOn, reason);
        transactions.save(voided);
        // The reason is read back off the aggregate rather than passed through, so the event carries
        // the stripped form that was actually stored.
        events.publish(new AccountingEvent.TransactionVoided(voided.id(), voidedOn, reason.strip()));
        return voided;
    }

    private Account require(AccountId id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    private Transaction require(TransactionId id) {
        Objects.requireNonNull(id, "id");
        return transactions.findById(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }
}
