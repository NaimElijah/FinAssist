package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;

/** Enters a balanced transaction in the journal. */
public interface RecordTransactionUseCase {

    /**
     * Records the transaction and stores it.
     *
     * @return the recorded transaction, {@code Pending} and carrying its generated identity
     * @throws AccountNotFoundException if an entry names an account that does not exist
     * @throws EntryCurrencyMismatchException if an entry is in a currency its account does not hold —
     *     the rule an {@code Entry} cannot check for itself, since it holds an {@code AccountId} rather
     *     than an {@code Account}
     * @throws IllegalArgumentException if the entries do not balance, from the domain
     */
    Transaction record(RecordTransactionCommand command);
}
