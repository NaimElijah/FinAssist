// @vitest-environment jsdom
// The first test in this codebase that needs a DOM. Node remains the default environment, so
// tests that read the source tree keep working - see the comment in vite.config.ts.

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
    it('Given_the_app_shell_When_rendered_Then_it_names_the_product', () => {
        render(<App />);

        // getByRole rather than getByText: it asserts the heading is a heading, which is the
        // part a screen reader depends on and the part a refactor to a styled div would break.
        expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('FinAssist');
    });

    it('Given_no_feature_is_built_When_rendered_Then_the_shell_says_so', () => {
        render(<App />);

        // Pinned deliberately. The README promises the web client "renders a placeholder", and
        // this is the assertion that has to be updated - and noticed - by whoever builds the
        // first feature.
        expect(screen.getByText(/no feature is implemented yet/i)).toBeTruthy();
    });
});
