// @vitest-environment jsdom
// Focus, form submission and clicks all need a DOM.

import { fireEvent, render, screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ActionPanel } from './ActionPanel';

/** The panel's form, failing loudly when it is absent. */
function panelForm(): HTMLFormElement {
    const form = screen.getByLabelText('Reason').closest('form');
    if (form === null) {
        throw new Error('ActionPanel rendered no form around its fields');
    }
    return form;
}

/**
 * A void panel with two caller-rendered fields. Plain labelled inputs rather than the field
 * primitives, so a failure here is ActionPanel's and not a field's.
 */
function renderVoidPanel(pending = false) {
    const onSubmit = vi.fn();
    const onCancel = vi.fn();
    const panel = (isPending: boolean) => (
        <ActionPanel
            title="Void transaction"
            submitLabel="Void"
            onSubmit={onSubmit}
            onCancel={onCancel}
            pending={isPending}
        >
            <label>
                Void on
                <input type="date" name="on" />
            </label>
            <label>
                Reason
                <input type="text" name="reason" />
            </label>
        </ActionPanel>
    );
    const { rerender } = render(panel(pending));
    /** Renders the panel again as its caller would once the request's state changes. */
    const renderPending = (isPending: boolean) => {
        rerender(panel(isPending));
    };
    return { onSubmit, onCancel, renderPending };
}

describe('ActionPanel', () => {
    it('Given_a_panel_When_rendered_Then_it_is_a_region_named_by_its_title', () => {
        renderVoidPanel();

        expect(screen.getByRole('region', { name: 'Void transaction' })).toBeDefined();
    });

    it('Given_no_heading_level_When_rendered_Then_its_title_is_a_level_3_heading', () => {
        // A panel sits inside a page, under its h1 and usually a section's h2.
        renderVoidPanel();

        expect(screen.getByRole('heading', { level: 3, name: 'Void transaction' })).toBeDefined();
    });

    for (const level of [2, 4] as const) {
        it(`Given_heading_level_${String(level)}_When_rendered_Then_its_title_is_a_level_${String(level)}_heading`, () => {
            render(
                <ActionPanel
                    title="Clear transaction"
                    submitLabel="Clear"
                    onSubmit={vi.fn()}
                    onCancel={vi.fn()}
                    pending={false}
                    headingLevel={level}
                >
                    <label>
                        Clear on
                        <input type="date" name="on" />
                    </label>
                </ActionPanel>,
            );

            expect(screen.getByRole('heading', { level, name: 'Clear transaction' })).toBeDefined();
        });
    }

    it('Given_a_panel_When_it_opens_Then_its_first_field_has_focus', () => {
        renderVoidPanel();

        expect(document.activeElement).toBe(screen.getByLabelText('Void on'));
    });

    it('Given_the_submit_button_When_clicked_Then_onSubmit_is_called_once', async () => {
        const { onSubmit } = renderVoidPanel();

        await userEvent.setup().click(screen.getByRole('button', { name: 'Void' }));

        expect(onSubmit).toHaveBeenCalledTimes(1);
    });

    it('Given_a_field_When_Enter_is_pressed_Then_onSubmit_is_called_once', async () => {
        const { onSubmit } = renderVoidPanel();

        await userEvent.setup().type(screen.getByLabelText('Reason'), 'Duplicate{Enter}');

        expect(onSubmit).toHaveBeenCalledTimes(1);
    });

    it('Given_the_cancel_button_When_clicked_Then_onCancel_is_called_and_onSubmit_is_not', async () => {
        const { onSubmit, onCancel } = renderVoidPanel();

        await userEvent.setup().click(screen.getByRole('button', { name: 'Cancel' }));

        expect(onCancel).toHaveBeenCalledTimes(1);
        expect(onSubmit).not.toHaveBeenCalled();
    });

    it('Given_pending_When_rendered_Then_submit_is_disabled_and_clicking_it_does_nothing', async () => {
        const { onSubmit } = renderVoidPanel(true);

        const submit = screen.getByRole('button', { name: 'Void' });
        await userEvent.setup().click(submit);

        expect(submit).toHaveProperty('disabled', true);
        expect(submit.getAttribute('aria-busy')).toBe('true');
        expect(onSubmit).not.toHaveBeenCalled();
    });

    it('Given_pending_When_the_form_is_submitted_anyway_Then_onSubmit_is_not_called', () => {
        // A disabled submit button stops a click, but a form can still receive a submit event -
        // from a script, or a browser whose implicit submission ignores the disabled button. The
        // event is fired directly so this fails whenever the panel itself does not guard.
        const { onSubmit } = renderVoidPanel(true);

        fireEvent.submit(panelForm());

        expect(onSubmit).not.toHaveBeenCalled();
    });

    it('Given_two_submit_events_before_pending_has_rendered_When_both_arrive_Then_onSubmit_is_called_once', () => {
        // The caller's pending prop renders a tick after the first submit starts its request, so
        // a guard reading that prop lets the second through: a repeated void is permanent in an
        // append-only journal (ADR-028).
        const { onSubmit } = renderVoidPanel();

        fireEvent.submit(panelForm());
        fireEvent.submit(panelForm());

        expect(onSubmit).toHaveBeenCalledTimes(1);
    });

    it('Given_a_request_that_was_pending_When_it_settles_Then_the_panel_can_be_submitted_again', () => {
        // A failed action leaves the panel open so the owner can correct it and send it again.
        const { onSubmit, renderPending } = renderVoidPanel();

        fireEvent.submit(panelForm());
        renderPending(true);
        renderPending(false);
        fireEvent.submit(panelForm());

        expect(onSubmit).toHaveBeenCalledTimes(2);
    });

    it('Given_a_request_that_settled_before_pending_ever_rendered_When_the_panel_renders_again_Then_it_can_be_submitted_again', () => {
        // A request that fails at once can settle before its pending state renders, so the
        // panel only ever sees pending false; waiting to see it turn true would lock the panel.
        const { onSubmit, renderPending } = renderVoidPanel();

        fireEvent.submit(panelForm());
        renderPending(false);
        fireEvent.submit(panelForm());

        expect(onSubmit).toHaveBeenCalledTimes(2);
    });

    it('Given_a_request_still_pending_When_the_panel_renders_again_Then_a_submit_is_still_ignored', () => {
        const { onSubmit, renderPending } = renderVoidPanel();

        fireEvent.submit(panelForm());
        renderPending(true);
        renderPending(true);
        fireEvent.submit(panelForm());

        expect(onSubmit).toHaveBeenCalledTimes(1);
    });
});
