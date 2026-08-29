package io.github.naimelijah.finassist.ledger.shared;

/**
 * The currencies FinAssist understands, with the number of decimal places each admits.
 *
 * <p>Deliberately not {@link java.util.Currency}. An explicit table keeps this enum comparable with
 * its Python counterpart in {@code finassist_assistant.shared}, which exists because ADR-010 forbids
 * a shared kernel across languages and asks for equivalent tests on both sides instead. A JDK lookup
 * on one side and a hand-written table on the other would drift without anything noticing.
 *
 * <p>Add a currency by adding a constant. Minor units come from ISO 4217.
 */
public enum Currency {
    ILS(2),
    USD(2),
    EUR(2),
    GBP(2),
    JPY(0);

    private final int minorUnits;

    Currency(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    /** Decimal places this currency admits: 2 for most, 0 for JPY. */
    public int minorUnits() {
        return minorUnits;
    }
}
