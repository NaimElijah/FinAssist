package io.github.naimelijah.finassist.ledger.planning.domain.plan;

/**
 * Which way a planned flow moves money, and therefore what it does to a projection.
 *
 * <p>The direction carries the sign, and a {@link PlannedFlow}'s amount is always positive. This is
 * deliberately the opposite choice from {@code accounting.Entry}, whose amount is signed: an entry
 * is signed because double entry needs entries to sum to zero, whereas a planned purchase of a
 * negative amount means nothing at all and should not be constructible.
 */
public enum Direction {
    /** Money arriving: salary, a gift, a refund. */
    INFLOW,
    /** Money leaving: the planned purchase itself, rent, a subscription. */
    OUTFLOW,
    /**
     * Money moving from cash into investments. It leaves the cash balance and joins the invested
     * balance, where it then compounds — which is what makes comparing "contribute 1000 a month"
     * against "contribute 500" a question with an answer.
     */
    INVESTMENT
}
