package io.github.naimelijah.finassist.ledger.shared;

/**
 * Thrown when an operation would combine amounts in different currencies.
 *
 * <p>FinAssist holds no exchange rates and performs no conversion (ADR-012), so adding ILS to USD
 * has no defined answer. Failing loudly is the only honest option: silently picking one currency, or
 * returning a bare number, would produce a total that looks right and is meaningless.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine " + left + " with " + right + "; FinAssist performs no conversion");
    }
}
