// @vitest-environment jsdom
// Reads the rendered <data> element, so it needs a DOM. Formatting itself is Node's ICU, the same
// Intl the browser build relies on; CI pins Node 24, so the exact strings below are stable there.

import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { MoneyText } from './MoneyText';

/** The single <data> element MoneyText renders, failing loudly when it is absent. */
function renderedData(amount: string, currency: string): HTMLDataElement {
    const { container } = render(<MoneyText amount={amount} currency={currency} />);
    const data = container.querySelector('data');
    if (data === null) {
        throw new Error('MoneyText rendered no <data> element');
    }
    return data;
}

describe('MoneyText', () => {
    it('Given_1234_50_ILS_When_rendered_Then_it_shows_the_en_IL_shekel_format', () => {
        expect(renderedData('1234.50', 'ILS').textContent).toBe('₪1,234.50');
    });

    it('Given_a_negative_USD_amount_When_rendered_Then_the_minus_sign_stays', () => {
        // The sign is text, so a negative amount never depends on colour to be read as one.
        expect(renderedData('-12.00', 'USD').textContent).toBe('-US$12.00');
    });

    it('Given_1200_JPY_When_rendered_Then_it_shows_no_minor_units', () => {
        expect(renderedData('1200', 'JPY').textContent).toBe('JP¥1,200');
    });

    it('Given_an_amount_beyond_float_precision_When_rendered_Then_every_digit_survives', () => {
        // Through a double this would read ₪12,345,678,901,234,568.00: the amount is formatted as
        // the decimal string it arrived as, never converted through Number (ADR-029, ADR-018).
        expect(renderedData('12345678901234567.89', 'ILS').textContent).toBe(
            '₪12,345,678,901,234,567.89',
        );
    });

    it('Given_an_amount_When_rendered_Then_the_data_value_is_the_amount_exactly_as_received', () => {
        expect(renderedData('-12.00', 'USD').value).toBe('-12.00');
    });

    it('Given_an_amount_that_is_not_a_decimal_string_When_rendered_Then_it_throws', () => {
        // Intl would otherwise print "₪NaN" - a wrong figure on screen, silently.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);

        expect(() => renderedData('12,50', 'ILS')).toThrow(/decimal/);

        vi.restoreAllMocks();
    });
});
