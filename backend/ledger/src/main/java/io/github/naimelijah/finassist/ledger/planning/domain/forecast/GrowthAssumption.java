package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.shared.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What invested money is assumed to do over one projection step.
 *
 * <p>The rate is <strong>per step</strong>, not per year. Converting an annual rate to a monthly one
 * needs a twelfth root, which {@link BigDecimal} cannot take exactly, so a domain offering
 * {@code annual(...)} would have to round silently and every figure downstream would inherit an
 * approximation nobody chose. {@code Money} already refuses to make that kind of decision on the
 * caller's behalf; so does this. A caller who thinks in annual terms converts visibly, where the
 * choice is reviewable.
 *
 * <p>A negative rate is allowed — an assumed decline is a legitimate thing to model. A rate at or
 * below minus one is not: it would wipe out or invert the balance, which no market does and no
 * projection should be able to claim.
 *
 * <p>The rate is bounded from above too (ADR-072): at most {@link #MAXIMUM_RATE}, 100% a step, and
 * at most {@link #MAXIMUM_DECIMAL_PLACES} decimal places. A projection compounds the rate at every
 * step across up to a century of weeks, so a rate beyond either bound costs far more to project
 * than any plan it could describe; above 1 it is also, most often, a percentage typed as a whole
 * number.
 */
public record GrowthAssumption(BigDecimal ratePerStep) {

    /**
     * The highest rate a step may assume: 1, which doubles the invested balance (ADR-072). {@code
     * DefineScenarioRequest}'s contract description states it in words.
     */
    public static final BigDecimal MAXIMUM_RATE = BigDecimal.ONE;

    /** The most decimal places a rate may carry once its trailing zeros are dropped (ADR-072). */
    public static final int MAXIMUM_DECIMAL_PLACES = 10;

    private static final BigDecimal MINIMUM_EXCLUSIVE = BigDecimal.ONE.negate();

    public GrowthAssumption {
        Objects.requireNonNull(ratePerStep, "ratePerStep");
        if (ratePerStep.compareTo(MINIMUM_EXCLUSIVE) <= 0) {
            // toString, never toPlainString, which writes a rate such as -1E+2147483647 out digit by
            // digit and throws OutOfMemoryError in place of this refusal.
            throw new IllegalArgumentException("A growth rate of " + ratePerStep.toString()
                    + " would destroy or invert the balance; it must be greater than -1");
        }
        // Neither upper refusal names the rate: a rate that passes the lower bound can be any length
        // the request allowed, and the message becomes a 422's detail.
        if (ratePerStep.compareTo(MAXIMUM_RATE) > 0) {
            throw new IllegalArgumentException("A growth rate is at most 1 a step, which doubles the invested"
                    + " balance; a rate of 5 means 500% a step, so write 5% as 0.05");
        }
        // Normalised for the same reason Money normalises its amount: a record's generated equals
        // compares BigDecimal with equals, which is scale-sensitive, so 0.1 and 0.10 would be two
        // different assumptions that compute identically - and Scenario, holding one, would inherit
        // the split. Citing Money's discipline while reproducing the hazard it exists to remove
        // would be worse than not citing it.
        ratePerStep = ratePerStep.stripTrailingZeros();
        // Counted after normalising, so 0.10000000000 is the 0.1 it computes as. Every step
        // multiplies by the rate at its full precision, which is the cost this bounds.
        if (ratePerStep.scale() > MAXIMUM_DECIMAL_PLACES) {
            throw new IllegalArgumentException(
                    "A growth rate has at most " + MAXIMUM_DECIMAL_PLACES + " decimal places");
        }
    }

    /** No growth at all — the assumption to use when modelling cash only. */
    public static GrowthAssumption none() {
        return new GrowthAssumption(BigDecimal.ZERO);
    }

    /**
     * Builds a rate from its decimal text, e.g. {@code perStep("0.005")} for 0.5% a step.
     *
     * @throws NumberFormatException if {@code rate} is not a decimal number
     * @throws IllegalArgumentException if the rate is at or below minus one, above
     *     {@link #MAXIMUM_RATE}, or has more than {@link #MAXIMUM_DECIMAL_PLACES} decimal places
     * @throws NullPointerException if {@code rate} is null
     */
    public static GrowthAssumption perStep(String rate) {
        return new GrowthAssumption(new BigDecimal(rate));
    }

    /**
     * The balance after one step of growth, rounded to the currency's minor units.
     *
     * <p>Rounded at every step rather than compounded exactly and rounded once at the end. Each
     * point of a projection has to be an amount that could actually appear in an account, and
     * consecutive points have to agree with each other; a series whose steps do not add up is worse
     * than one that carries a little rounding drift over a long horizon.
     */
    public Money applyTo(Money invested) {
        Objects.requireNonNull(invested, "invested");
        return invested.timesRounded(BigDecimal.ONE.add(ratePerStep));
    }
}
