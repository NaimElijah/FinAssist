// @vitest-environment node
// A plain function over a mutation's status and reset: no DOM needed.

import { describe, expect, it, vi } from 'vitest';

import { clearSettledOutcome, type SettleableMutation } from './settledMutation';

/** A mutation in `status`, with a reset the test can watch. */
function mutationIn(status: SettleableMutation['status']) {
    const reset = vi.fn<() => void>();
    return { mutation: { status, reset }, reset };
}

/** A form's in-flight guard with no request running. */
const IDLE_FORM = { current: false } as const;

/** A form's in-flight guard set by a submit whose request has not settled. */
const SUBMITTING_FORM = { current: true } as const;

describe('clearSettledOutcome', () => {
    it('Given_a_mutation_that_failed_When_its_outcome_is_cleared_Then_it_is_reset', () => {
        const { mutation, reset } = mutationIn('error');

        clearSettledOutcome(mutation, IDLE_FORM);

        expect(reset).toHaveBeenCalledTimes(1);
    });

    it('Given_a_mutation_that_succeeded_When_its_outcome_is_cleared_Then_it_is_reset', () => {
        const { mutation, reset } = mutationIn('success');

        clearSettledOutcome(mutation, IDLE_FORM);

        expect(reset).toHaveBeenCalledTimes(1);
    });

    it('Given_a_mutation_still_in_flight_When_its_outcome_is_cleared_Then_it_is_left_alone', () => {
        // Resetting one would detach the form from it, so the onSettled releasing the form's
        // in-flight guard would never run.
        const { mutation, reset } = mutationIn('pending');

        clearSettledOutcome(mutation, SUBMITTING_FORM);

        expect(reset).not.toHaveBeenCalled();
    });

    it('Given_a_mutation_never_sent_When_its_outcome_is_cleared_Then_nothing_is_reset', () => {
        const { mutation, reset } = mutationIn('idle');

        clearSettledOutcome(mutation, IDLE_FORM);

        expect(reset).not.toHaveBeenCalled();
    });

    it('Given_a_rendered_failure_whose_form_has_a_request_in_flight_When_its_outcome_is_cleared_Then_it_is_left_alone', () => {
        // A resubmit's pending status renders a tick after it starts, so for that tick the
        // status still reads the last failure while a request is running.
        const { mutation, reset } = mutationIn('error');

        clearSettledOutcome(mutation, SUBMITTING_FORM);

        expect(reset).not.toHaveBeenCalled();
    });

    it('Given_a_rendered_success_whose_form_has_a_request_in_flight_When_its_outcome_is_cleared_Then_it_is_left_alone', () => {
        const { mutation, reset } = mutationIn('success');

        clearSettledOutcome(mutation, SUBMITTING_FORM);

        expect(reset).not.toHaveBeenCalled();
    });
});
