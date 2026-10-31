package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.shared.Currency;

/**
 * An entry was posted to an account that does not hold that currency.
 *
 * <p>ADR-012 gives an account exactly one currency, and the domain cannot check an entry against it:
 * an {@code Entry} holds an {@code AccountId}, not an {@code Account}, so there is nothing to compare
 * without a repository. Until this existed a mis-currencied entry was caught later and much less
 * helpfully, as a {@code CurrencyMismatchException} thrown while summing a balance.
 */
public class EntryCurrencyMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EntryCurrencyMismatchException(Account account, Currency entryCurrency) {
        super("Entry for account '" + account.name() + "' is in " + entryCurrency + " but the account is in "
                + account.currency() + "; FinAssist performs no conversion");
    }
}
