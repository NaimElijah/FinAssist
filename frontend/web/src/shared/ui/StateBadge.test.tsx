// @vitest-environment jsdom
// Reads the rendered badge, so it needs a DOM.

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { StateBadge, type Tone } from './StateBadge';

type TransactionStatus = 'PENDING' | 'CLEARED' | 'VOIDED';

const TONES: Readonly<Record<TransactionStatus, Tone>> = {
    PENDING: 'warning',
    CLEARED: 'positive',
    VOIDED: 'negative',
};

describe('StateBadge', () => {
    it('Given_a_status_and_a_label_When_rendered_Then_the_label_is_the_text_and_the_tone_is_a_style_hook', () => {
        render(<StateBadge<TransactionStatus> status="VOIDED" tones={TONES} label="Voided" />);

        const badge = screen.getByText('Voided');
        expect(badge.getAttribute('data-tone')).toBe('negative');
    });

    it('Given_a_status_with_no_label_When_rendered_Then_the_status_itself_is_the_text', () => {
        render(<StateBadge<TransactionStatus> status="PENDING" tones={TONES} />);

        expect(screen.getByText('PENDING').getAttribute('data-tone')).toBe('warning');
    });
});
