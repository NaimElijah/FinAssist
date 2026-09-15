// @vitest-environment node
// A plain function over a mutation's status and reset: no DOM needed.

import { describe, expect, it, vi } from 'vitest';

import { clearSettledOutcome, type SettleableMutation } from './settledMutation';

/** A mutation in `status`, with a reset the test can watch. */
function mutationIn(status: SettleableMutation['status']) {
    const reset = vi.fn<() => void>();
    return { mutation: { status, reset }, reset };
}

describe('clearSettledOutcome', () => {
    it('Given_a_mutation_that_failed_When_its_outcome_is_cleared_Then_it_is_reset', () => {
        const { mutation, reset } = mutationIn('error');

        clearSettledOutcome(mutation);

        expect(reset).toHaveBeenCalledTimes(1);
    });

    it('Given_a_mutation_that_succeeded_When_its_outcome_is_cleared_Then_it_is_reset', () => {
        const { mutation, reset } = mutationIn('success');

        clearSettledOutcome(mutation);

        expect(reset).toHaveBeenCalledTimes(1);
    });

    it('Given_a_mutation_still_in_flight_When_its_outcome_is_cleared_Then_it_is_left_alone', () => {
        // Resetting one would detach the form from it, so the onSettled releasing the form's
        // in-flight guard would never run.
        const { mutation, reset } = mutationIn('pending');

        clearSettledOutcome(mutation);

        expect(reset).not.toHaveBeenCalled();
    });

    it('Given_a_mutation_never_sent_When_its_outcome_is_cleared_Then_nothing_is_reset', () => {
        const { mutation, reset } = mutationIn('idle');

        clearSettledOutcome(mutation);

        expect(reset).not.toHaveBeenCalled();
    });
});
