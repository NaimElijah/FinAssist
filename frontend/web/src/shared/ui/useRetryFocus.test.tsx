// @vitest-environment jsdom
// Moves real focus between rendered elements, so it needs a DOM.

import { render, screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { useRetryFocus } from './useRetryFocus';

/** A read as a screen passes it to the hook: what it failed with, and whether it is in flight. */
interface ReadState {
    readonly error: unknown;
    readonly retrying: boolean;
}

const FAILED: ReadState = { error: new TypeError('Failed to fetch'), retrying: false };
const RETRYING: ReadState = { error: FAILED.error, retrying: true };
const RECOVERED: ReadState = { error: null, retrying: false };

/** A retry button while the read has failed, and the content a successful retry restores. */
function Screen({ read, retry }: { readonly read: ReadState; readonly retry: () => void }) {
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(read.error, read.retrying, retry);
    return (
        <>
            {read.error === null ? null : (
                <button type="button" onClick={onRetry}>
                    Try again
                </button>
            )}
            <div ref={targetRef} tabIndex={-1}>
                Restored content
            </div>
        </>
    );
}

/** Click the screen's retry, as the owner would. */
async function clickTryAgain() {
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Try again' }));
}

describe('useRetryFocus', () => {
    it('Given_a_retry_the_owner_asked_for_When_it_succeeds_Then_focus_moves_to_the_restored_content', async () => {
        const retry = vi.fn<() => void>();
        const { rerender } = render(<Screen read={FAILED} retry={retry} />);

        await clickTryAgain();
        rerender(<Screen read={RETRYING} retry={retry} />);
        rerender(<Screen read={RECOVERED} retry={retry} />);

        expect(retry).toHaveBeenCalledTimes(1);
        expect(document.activeElement).toBe(screen.getByText('Restored content'));
    });

    it('Given_a_retry_still_in_flight_When_the_read_has_no_error_yet_Then_focus_has_not_moved', async () => {
        // A read with nothing held clears its error as soon as a retry starts; the content it
        // restores is not on screen until the retry settles.
        const retry = vi.fn<() => void>();
        const { rerender } = render(<Screen read={FAILED} retry={retry} />);

        await clickTryAgain();
        rerender(<Screen read={{ error: null, retrying: true }} retry={retry} />);

        expect(document.activeElement).not.toBe(screen.getByText('Restored content'));
    });

    it('Given_a_retry_the_owner_asked_for_When_it_fails_again_Then_focus_is_left_to_the_failure', async () => {
        const retry = vi.fn<() => void>();
        const { rerender } = render(<Screen read={FAILED} retry={retry} />);
        const button = screen.getByRole('button', { name: 'Try again' });

        await clickTryAgain();
        rerender(<Screen read={RETRYING} retry={retry} />);
        rerender(
            <Screen
                read={{ error: new TypeError('Failed again'), retrying: false }}
                retry={retry}
            />,
        );

        expect(document.activeElement).toBe(button);
    });

    it('Given_a_failed_read_that_recovers_without_a_retry_asked_for_When_it_succeeds_Then_focus_does_not_move', () => {
        // A background refetch that happens to succeed must never take focus from the page.
        const retry = vi.fn<() => void>();
        const { rerender } = render(<Screen read={FAILED} retry={retry} />);

        rerender(<Screen read={RETRYING} retry={retry} />);
        rerender(<Screen read={RECOVERED} retry={retry} />);

        expect(document.activeElement).toBe(document.body);
        expect(retry).not.toHaveBeenCalled();
    });

    it('Given_focus_already_taken_by_something_else_When_the_retry_succeeds_Then_it_is_left_there', async () => {
        // Such as a list's newly opened row, which takes focus as it renders: that is the more
        // specific answer, and the retry only rescues focus the page has lost.
        const retry = vi.fn<() => void>();
        const { rerender } = render(<Screen read={FAILED} retry={retry} />);
        const elsewhere = document.createElement('input');
        document.body.append(elsewhere);

        await clickTryAgain();
        rerender(<Screen read={RETRYING} retry={retry} />);
        elsewhere.focus();
        rerender(<Screen read={RECOVERED} retry={retry} />);

        expect(document.activeElement).toBe(elsewhere);
        elsewhere.remove();
    });

    it('Given_a_retry_that_already_moved_focus_When_a_later_failure_recovers_by_itself_Then_focus_does_not_move_again', async () => {
        const retry = vi.fn<() => void>();
        const { rerender } = render(<Screen read={FAILED} retry={retry} />);
        await clickTryAgain();
        rerender(<Screen read={RETRYING} retry={retry} />);
        rerender(<Screen read={RECOVERED} retry={retry} />);
        const content = screen.getByText('Restored content');
        content.blur();

        rerender(
            <Screen read={{ error: new TypeError('Later'), retrying: false }} retry={retry} />,
        );
        rerender(<Screen read={RECOVERED} retry={retry} />);

        expect(document.activeElement).toBe(document.body);
    });
});
