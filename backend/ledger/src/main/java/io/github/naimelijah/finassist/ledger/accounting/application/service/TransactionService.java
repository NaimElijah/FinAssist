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
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

        // A set, because the event documents these as the accounts the transaction touches. One
        // transaction may post twice to the same account - a payment split across two categories, say -
        // and a subscriber folding over the list would then count it twice.
        Set<AccountId> touched = new LinkedHashSet<>();
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
                transaction.id(), transaction.occurredOn(), transaction.description(), List.copyOf(touched)));
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
        // Genuinely read off the aggregate now, not re-derived from the input. The two agree only
        // because Voided happens to normalise with strip() today; the day it collapses whitespace or
        // truncates, a re-derived event would carry a reason that was never stored, and no test would
        // notice because both sides are asserted against the same literal. The cast is deliberate -
        // markVoided returns Voided or throws - and fails loudly rather than adding an unreachable
        // branch for a test to chase.
        TransactionState.Voided state = (TransactionState.Voided) voided.state();
        events.publish(new AccountingEvent.TransactionVoided(voided.id(), state.voidedOn(), state.reason()));
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
