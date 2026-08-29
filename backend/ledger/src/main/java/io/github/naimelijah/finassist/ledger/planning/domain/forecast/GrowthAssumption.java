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
 */
public record GrowthAssumption(BigDecimal ratePerStep) {

    private static final BigDecimal MINIMUM_EXCLUSIVE = BigDecimal.ONE.negate();

    public GrowthAssumption {
        Objects.requireNonNull(ratePerStep, "ratePerStep");
        if (ratePerStep.compareTo(MINIMUM_EXCLUSIVE) <= 0) {
            throw new IllegalArgumentException("A growth rate of " + ratePerStep.toPlainString()
                    + " would destroy or invert the balance; it must be greater than -1");
        }
    }

    /** No growth at all — the assumption to use when modelling cash only. */
    public static GrowthAssumption none() {
        return new GrowthAssumption(BigDecimal.ZERO);
    }

    /** Builds a rate from its decimal text, e.g. {@code perStep("0.005")} for 0.5% a step. */
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
