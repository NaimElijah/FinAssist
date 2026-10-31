// @vitest-environment jsdom
// Label, hint and error association is resolved through DOM ids, so it needs a DOM.
/**
 * The three labelled fields share one contract, so one table of cases drives all three through
 * their own public components rather than through the frame they have in common.
 */

import { render, screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';

import { DateField } from './DateField';
import { MonthField } from './MonthField';
import { SelectField } from './SelectField';
import { TextField } from './TextField';

interface CommonFieldProps {
    readonly label: string;
    readonly hint?: string;
    readonly error?: string;
    readonly required?: boolean;
    readonly sharedErrorId?: string;
    readonly sharedHintId?: string;
}

/** The visible text that marks a field the form cannot be submitted without. */
const REQUIRED_MARKER = '(required)';

const FIELDS: readonly {
    readonly name: string;
    readonly renderField: (props: CommonFieldProps) => ReactElement;
}[] = [
    { name: 'TextField', renderField: (props) => <TextField {...props} /> },
    { name: 'DateField', renderField: (props) => <DateField {...props} /> },
    { name: 'MonthField', renderField: (props) => <MonthField {...props} /> },
    {
        name: 'SelectField',
        renderField: (props) => (
            <SelectField {...props} options={[{ value: 'checking', label: 'Checking' }]} />
        ),
    },
];

/** The text of every element the control's `aria-describedby` names, in order. */
function descriptionsOf(control: HTMLElement): (string | null | undefined)[] {
    const ids = (control.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean);
    return ids.map((id) => document.getElementById(id)?.textContent);
}

describe('labelled fields', () => {
    // A plain loop rather than it.each, as in tests/architecture.test.ts: one named test per
    // field.
    for (const { name, renderField } of FIELDS) {
        it(`Given_a_${name}_with_a_label_When_rendered_Then_the_label_names_its_control`, () => {
            render(renderField({ label: 'Account' }));

            expect(screen.getByLabelText('Account')).toBeDefined();
        });

        it(`Given_a_${name}_with_a_hint_and_an_error_When_rendered_Then_both_describe_the_control_and_it_is_invalid`, () => {
            render(
                renderField({
                    label: 'Account',
                    hint: 'The account the money moves through.',
                    error: 'Choose an account.',
                }),
            );

            const control = screen.getByLabelText('Account');
            expect(descriptionsOf(control)).toEqual([
                'The account the money moves through.',
                'Choose an account.',
            ]);
            expect(control.getAttribute('aria-invalid')).toBe('true');
        });

        it(`Given_a_${name}_with_no_hint_or_error_When_rendered_Then_it_is_neither_described_nor_invalid`, () => {
            render(renderField({ label: 'Account' }));

            const control = screen.getByLabelText('Account');
            expect(control.hasAttribute('aria-describedby')).toBe(false);
            expect(control.hasAttribute('aria-invalid')).toBe(false);
        });

        it(`Given_a_required_${name}_When_rendered_Then_a_visible_marker_says_so_and_the_control_is_required`, () => {
            render(renderField({ label: 'Account', required: true }));

            // The label alone still names the control: the native attribute already announces
            // the state, so the marker is hidden from assistive technology rather than read twice.
            const control = screen.getByLabelText('Account');
            expect(control).toHaveProperty('required', true);
            const marker = screen.getByText(REQUIRED_MARKER);
            expect(marker.closest('[aria-hidden="true"]')).not.toBeNull();
            expect(marker.closest('[hidden]')).toBeNull();
        });

        it(`Given_a_${name}_sharing_an_error_shown_elsewhere_When_rendered_Then_it_is_invalid_and_described_by_that_one_message`, () => {
            render(
                <>
                    <p id="pair-error">Enter a debit or a credit</p>
                    {renderField({
                        label: 'Account',
                        hint: 'The account the money moves through.',
                        sharedErrorId: 'pair-error',
                    })}
                </>,
            );

            const control = screen.getByLabelText('Account');
            expect(descriptionsOf(control)).toEqual([
                'The account the money moves through.',
                'Enter a debit or a credit',
            ]);
            expect(control.getAttribute('aria-invalid')).toBe('true');
            // The field renders no copy of its own, so the message is shown and read once.
            expect(screen.getAllByText('Enter a debit or a credit')).toHaveLength(1);
        });

        it(`Given_a_${name}_sharing_a_hint_shown_elsewhere_When_rendered_Then_it_is_described_by_its_own_hint_that_hint_then_its_errors_and_shown_once`, () => {
            render(
                <>
                    <p id="rows-hint">Each entry is a debit or a credit, not both.</p>
                    <p id="pair-error">Enter a debit or a credit</p>
                    {renderField({
                        label: 'Account',
                        hint: 'The account the money moves through.',
                        sharedHintId: 'rows-hint',
                        error: 'Choose an account.',
                        sharedErrorId: 'pair-error',
                    })}
                </>,
            );

            // Guidance before errors, the field's own first: a screen reader reads what the
            // field takes, then what is wrong with it.
            expect(descriptionsOf(screen.getByLabelText('Account'))).toEqual([
                'The account the money moves through.',
                'Each entry is a debit or a credit, not both.',
                'Choose an account.',
                'Enter a debit or a credit',
            ]);
            expect(
                screen.getAllByText('Each entry is a debit or a credit, not both.'),
            ).toHaveLength(1);
        });

        it(`Given_a_${name}_sharing_only_a_hint_When_rendered_Then_it_is_described_by_it_and_not_invalid`, () => {
            render(
                <>
                    <p id="rows-hint">Each entry is a debit or a credit, not both.</p>
                    {renderField({ label: 'Account', sharedHintId: 'rows-hint' })}
                </>,
            );

            const control = screen.getByLabelText('Account');
            expect(descriptionsOf(control)).toEqual([
                'Each entry is a debit or a credit, not both.',
            ]);
            expect(control.hasAttribute('aria-invalid')).toBe(false);
        });

        it(`Given_an_optional_${name}_When_rendered_Then_no_required_marker_shows`, () => {
            render(renderField({ label: 'Account' }));

            expect(screen.getByLabelText('Account')).toHaveProperty('required', false);
            expect(screen.queryByText(REQUIRED_MARKER)).toBeNull();
        });
    }

    it('Given_two_fields_with_the_same_label_text_When_rendered_Then_each_label_names_its_own_control', () => {
        // Ids come from useId, so two instances never collide on the same id.
        render(
            <>
                <TextField label="Memo" />
                <TextField label="Memo" />
            </>,
        );

        const [first, second] = screen.getAllByLabelText('Memo');
        expect(first).toBeDefined();
        expect(first).not.toBe(second);
    });

    it('Given_a_TextField_When_typed_into_Then_native_input_props_are_passed_through', async () => {
        render(<TextField label="Amount" inputMode="decimal" defaultValue="" />);

        const input = screen.getByLabelText('Amount');
        await userEvent.setup().type(input, '12.50');

        expect(input).toHaveProperty('value', '12.50');
        expect(input.getAttribute('inputmode')).toBe('decimal');
        expect(input.getAttribute('type')).toBe('text');
    });

    it('Given_a_DateField_When_rendered_Then_it_is_a_native_date_input', () => {
        render(<DateField label="Occurred on" defaultValue="2026-09-01" />);

        const input = screen.getByLabelText('Occurred on');
        expect(input.getAttribute('type')).toBe('date');
        expect(input).toHaveProperty('value', '2026-09-01');
    });

    it('Given_a_MonthField_When_rendered_Then_it_is_a_native_month_input', () => {
        render(<MonthField label="Month" defaultValue="2026-09" />);

        const input = screen.getByLabelText('Month');
        expect(input.getAttribute('type')).toBe('month');
        expect(input).toHaveProperty('value', '2026-09');
    });

    it('Given_a_SelectField_with_options_When_one_is_chosen_Then_its_value_is_selected', async () => {
        render(
            <SelectField
                label="Currency"
                defaultValue="ILS"
                options={[
                    { value: 'ILS', label: 'Israeli new shekel' },
                    { value: 'USD', label: 'US dollar' },
                ]}
            />,
        );

        const select = screen.getByLabelText('Currency');
        await userEvent.setup().selectOptions(select, 'USD');

        expect(select).toHaveProperty('value', 'USD');
        expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
            'Israeli new shekel',
            'US dollar',
        ]);
    });
});
