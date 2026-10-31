package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;

/**
 * What the contract says about a horizon's bounds, shared by the projection, the comparison and {@link
 * HorizonResponse} so the three cannot describe one span two ways.
 *
 * <p>The wording follows {@link Horizon} and {@code Projection}: the span is half-open for the flows it
 * counts, yet a point is reported on both ends, so neither "inclusive" nor "exclusive" alone is true of
 * {@code to}.
 */
final class HorizonDescriptions {

    static final String FROM = "The horizon's start, inclusive, and the date of its first point.";

    static final String TO = "The horizon's end and the date of its last point. Exclusive for flows: one dated on"
            + " it is not counted.";

    static final String FROM_PARAMETER = FROM + " That point is the opening position, before any flow or growth.";

    static final String TO_PARAMETER = TO + " It must fall a whole number of steps after from, and at most "
            + Horizon.MAXIMUM_SPAN_YEARS + " years after it.";

    static final String STEP_PARAMETER =
            "The spacing of the points: each is the horizon's start advanced by a whole number of steps.";

    private HorizonDescriptions() {}
}
