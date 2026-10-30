package io.github.naimelijah.finassist.ledger.composition;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewBalanceUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPosition;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPositionProvider;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.List;
import java.util.Objects;

/**
 * Gives planning the balances a projection opens from, by asking accounting.
 *
 * <p>The other half of what the composition root is for. Planning addresses accounts with its own
 * {@link AccountRef} and never learns that accounting identifies them with a UUID; turning one into
 * the other happens here and nowhere else (ADR-022).
 *
 * <p><strong>Accounting's exceptions stop here.</strong> A bad reference surfaces as an
 * {@code IllegalArgumentException} naming the reference planning gave, not as an
 * {@code AccountNotFoundException} carrying an id planning never saw — letting that through would put
 * an accounting type in planning's call stack, which is the leak the boundary exists to prevent.
 *
 * <p>It refuses only what needs an accounting value to state: a reference that is not an account
 * identifier, one that names no account, and an account held in another currency than the scenario,
 * since FinAssist performs no conversion (ADR-012). An account named twice is planning's own rule,
 * stated in its own {@link AccountRef}s, so planning's commands refuse it before this is asked
 * (ADR-051).
 */
public class AccountingOpeningPositions implements OpeningPositionProvider {

    private final ViewBalanceUseCase balances;

    public AccountingOpeningPositions(ViewBalanceUseCase balances) {
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    @Override
    public OpeningPosition positionOf(List<AccountRef> cash, List<AccountRef> invested, Currency currency) {
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(invested, "invested");
        Objects.requireNonNull(currency, "currency");

        return new OpeningPosition(sum(cash, currency), sum(invested, currency));
    }

    private Money sum(List<AccountRef> refs, Currency currency) {
        Money total = Money.zero(currency);
        for (AccountRef ref : refs) {
            Balance balance = balanceOf(ref);
            if (balance.amount().currency() != currency) {
                throw new IllegalArgumentException("Account " + ref + " is held in "
                        + balance.amount().currency() + " but the scenario is in " + currency
                        + "; FinAssist performs no conversion");
            }
            total = total.plus(balance.amount());
        }
        return total;
    }

    private Balance balanceOf(AccountRef ref) {
        AccountId id;
        try {
            id = AccountId.of(ref.value());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Account reference " + ref + " is not an account identifier", malformed);
        }
        try {
            return balances.balanceOf(id);
        } catch (AccountNotFoundException unknown) {
            throw new IllegalArgumentException("Account reference " + ref + " names no account", unknown);
        }
    }
}
