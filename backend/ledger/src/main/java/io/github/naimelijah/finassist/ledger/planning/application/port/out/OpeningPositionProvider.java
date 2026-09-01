package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.List;

/**
 * Where planning asks what it is starting from.
 *
 * <p>{@code Projection.of} takes an opening balance as a bare {@code Money} and ADR-015 names the
 * consequence: "the application layer must get the currency right; the domain can only refuse a
 * mismatch, not prevent one being offered." This port is where that responsibility lives.
 *
 * <p>Which accounts count as cash and which as investments is <strong>the caller's</strong>, passed
 * down from {@code ProjectScenarioCommand}. Deciding it here would be inventing a policy the
 * requirements do not state — there is no rule anywhere in FinAssist that says an {@code ASSET} named
 * one thing is cash and another is an investment, and guessing one would bury it in a port.
 *
 * <p>Nothing inside the ledger implements this. The translation over accounting lives in the
 * composition root (ADR-022).
 */
public interface OpeningPositionProvider {

    /**
     * Sums the named accounts into an opening position, in the given currency and no other.
     *
     * @param cash accounts whose balances open the cash line; empty means an opening cash of zero
     * @param invested accounts whose balances open the invested line; empty means zero
     * @param currency the scenario's currency, which the answer must be in
     * @throws IllegalArgumentException if a reference names no account, or names one held in another
     *     currency — FinAssist holds no rates, so there is nothing sensible to return (ADR-012)
     */
    OpeningPosition positionOf(List<AccountRef> cash, List<AccountRef> invested, Currency currency);
}
