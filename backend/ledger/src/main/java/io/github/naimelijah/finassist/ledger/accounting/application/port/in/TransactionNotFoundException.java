package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;

/** No transaction of that identity is in the journal. */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(TransactionId transaction) {
        super("No transaction with id " + transaction);
    }
}
