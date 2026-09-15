// @vitest-environment jsdom
// Focus, form submission and clicks all need a DOM.

import { fireEvent, render, screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ActionPanel } from './ActionPanel';

/**
 * A void panel with two caller-rendered fields. Plain labelled inputs rather than the field
 * primitives, so a failure here is ActionPanel's and not a field's.
 */
function renderVoidPanel(pending = false) {
    const onSubmit = vi.fn();
    const onCancel = vi.fn();
    render(
        <ActionPanel
            title="Void transaction"
            submitLabel="Void"
            onSubmit={onSubmit}
            onCancel={onCancel}
            pending={pending}
        >
            <label>
                Void on
                <input type="date" name="on" />
            </label>
            <label>
                Reason
                <input type="text" name="reason" />
            </label>
        </ActionPanel>,
    );
    return { onSubmit, onCancel };
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

        const form = screen.getByLabelText('Reason').closest('form');
        if (form === null) {
            throw new Error('ActionPanel rendered no form around its fields');
        }
        fireEvent.submit(form);

        expect(onSubmit).not.toHaveBeenCalled();
    });
});
