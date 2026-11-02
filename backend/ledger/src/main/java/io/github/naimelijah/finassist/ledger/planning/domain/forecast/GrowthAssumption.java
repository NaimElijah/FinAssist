package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final String TOO_PRECISE = "A growth rate has at most " + MAXIMUM_DECIMAL_PLACES + " decimal places";

    public GrowthAssumption {
        Objects.requireNonNull(ratePerStep, "ratePerStep");
        // No refusal names the rate: it is the caller's text, as long as the request that carried it
        // allowed, and the message becomes a 422's detail (ADR-072).
        if (ratePerStep.compareTo(MINIMUM_EXCLUSIVE) <= 0) {
            throw new IllegalArgumentException(
                    "A growth rate must be greater than -1; at or below it, it would destroy or invert the balance");
        }
        if (ratePerStep.compareTo(MAXIMUM_RATE) > 0) {
            throw new IllegalArgumentException("A growth rate is at most 1 a step, which doubles the invested"
                    + " balance; a rate of 5 means 500% a step, so write 5% as 0.05");
        }
        ratePerStep = normalised(ratePerStep);
    }

    /**
     * The rate with its trailing zeros dropped, refused if it then has more than {@link
     * #MAXIMUM_DECIMAL_PLACES}.
     *
     * <p>Normalised for the same reason Money normalises its amount: a record's generated equals
     * compares BigDecimal with equals, which is scale-sensitive, so 0.1 and 0.10 would be two
     * different assumptions that compute identically - and Scenario, holding one, would inherit the
     * split. Citing Money's discipline while reproducing the hazard it exists to remove would be worse
     * than not citing it.
     *
     * <p>Places are counted as the zeros would leave them, so 0.10000000000 is the 0.1 it computes
     * as, but the rate is cut to the allowed places, exactly or not at all, before anything is
     * stripped: {@code stripTrailingZeros} takes time that grows with the square of the zeros it
     * drops, and a rate is the caller's text, padded to the length of the request if they choose.
     */
    private static BigDecimal normalised(BigDecimal rate) {
        if (rate.signum() == 0) {
            return BigDecimal.ZERO;
        }
        // Its first significant digit lies past the last place allowed, so no cut can keep it. Checked
        // before setScale, which would otherwise compute a power of ten as large as the rate's scale.
        if ((long) rate.scale() - rate.precision() >= MAXIMUM_DECIMAL_PLACES) {
            throw new IllegalArgumentException(TOO_PRECISE);
        }
        BigDecimal cut = rate;
        if (rate.scale() > MAXIMUM_DECIMAL_PLACES) {
            try {
                cut = rate.setScale(MAXIMUM_DECIMAL_PLACES, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException digitPastTheLastPlace) {
                throw new IllegalArgumentException(TOO_PRECISE, digitPastTheLastPlace);
            }
        }
        return cut.stripTrailingZeros();
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
