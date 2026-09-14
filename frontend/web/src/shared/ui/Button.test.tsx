// @vitest-environment jsdom
// Renders a real button and clicks it, so it needs a DOM.

import { render, screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Button } from './Button';

describe('Button', () => {
    it('Given_pending_When_rendered_Then_it_is_disabled_and_busy', () => {
        render(<Button pending>Record transaction</Button>);

        const button = screen.getByRole('button', { name: 'Record transaction' });
        expect(button).toHaveProperty('disabled', true);
        expect(button.getAttribute('aria-busy')).toBe('true');
    });

    it('Given_pending_When_clicked_Then_onClick_is_not_called', async () => {
        // ADR-028: a double-submitted create is permanent, so a pending button must not act.
        const onClick = vi.fn();
        render(
            <Button pending onClick={onClick}>
                Save
            </Button>,
        );

        await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }));

        expect(onClick).not.toHaveBeenCalled();
    });

    it('Given_not_pending_When_clicked_Then_onClick_is_called_once', async () => {
        const onClick = vi.fn();
        render(<Button onClick={onClick}>Save</Button>);

        const button = screen.getByRole('button', { name: 'Save' });
        await userEvent.setup().click(button);

        expect(onClick).toHaveBeenCalledTimes(1);
        expect(button).toHaveProperty('disabled', false);
        expect(button.getAttribute('aria-busy')).toBe('false');
    });

    it('Given_no_type_When_clicked_inside_a_form_Then_the_form_is_not_submitted', async () => {
        const onSubmit = vi.fn((event: { preventDefault: () => void }) => {
            event.preventDefault();
        });
        render(
            <form onSubmit={onSubmit}>
                <Button variant="secondary">Cancel</Button>
            </form>,
        );

        await userEvent.setup().click(screen.getByRole('button', { name: 'Cancel' }));

        expect(onSubmit).not.toHaveBeenCalled();
    });

    it('Given_type_submit_When_clicked_inside_a_form_Then_the_form_is_submitted', async () => {
        const onSubmit = vi.fn((event: { preventDefault: () => void }) => {
            event.preventDefault();
        });
        render(
            <form onSubmit={onSubmit}>
                <Button type="submit">Record</Button>
            </form>,
        );

        await userEvent.setup().click(screen.getByRole('button', { name: 'Record' }));

        expect(onSubmit).toHaveBeenCalledTimes(1);
    });
});
