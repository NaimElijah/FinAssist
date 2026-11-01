package io.github.naimelijah.finassist.ledger.shared;

/**
 * Thrown when an operation would combine amounts in different currencies.
 *
 * <p>FinAssist holds no exchange rates and performs no conversion (ADR-012), so adding ILS to USD
 * has no defined answer. Failing loudly is the only honest option: silently picking one currency, or
 * returning a bare number, would produce a total that looks right and is meaningless.
 */
public class CurrencyMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for an attempt to combine {@code left} with {@code right}.
     *
     * @param left the currency of the first amount; it leads in the message
     * @param right the currency of the second amount
     */
    public CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine " + left + " with " + right + "; FinAssist performs no conversion");
    }
}
