// @vitest-environment jsdom
// Reads the rendered <time> element, so it needs a DOM. The timezone cases below set
// `process.env.TZ` at runtime: Node re-reads it on assignment, for Date and for Intl's default
// zone alike, and the control case proves the switch really took effect in this test process.

import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DateText } from './DateText';

/** Zones on both sides of UTC, including the furthest ahead and a negative offset. */
const ZONES = ['UTC', 'America/Los_Angeles', 'Pacific/Kiritimati', 'Asia/Jerusalem'] as const;

/** The single <time> element DateText renders, failing loudly when it is absent. */
function renderedTime(value: string): HTMLTimeElement {
    const { container } = render(<DateText value={value} />);
    const time = container.querySelector('time');
    if (time === null) {
        throw new Error('DateText rendered no <time> element');
    }
    return time;
}

describe('DateText', () => {
    let originalZone: string | undefined;

    beforeEach(() => {
        originalZone = process.env.TZ;
    });

    afterEach(() => {
        if (originalZone === undefined) {
            delete process.env.TZ;
        } else {
            process.env.TZ = originalZone;
        }
        vi.restoreAllMocks();
    });

    it('Given_a_negative_offset_zone_When_an_ISO_date_is_parsed_by_Date_Then_the_local_day_shifts', () => {
        // The control for the cases below, and the bug DateText exists to avoid: `new Date` reads
        // a bare YYYY-MM-DD as UTC midnight, which is the previous day west of Greenwich. If
        // this ever stops holding, the zone switch is not taking effect and the loop proves
        // nothing.
        process.env.TZ = 'America/Los_Angeles';

        expect(new Date('2026-09-01').getDate()).toBe(31);
    });

    for (const zone of ZONES) {
        it(`Given_2026_09_01_When_rendered_in_${zone.replace('/', '_')}_Then_it_shows_1_September`, () => {
            process.env.TZ = zone;

            const time = renderedTime('2026-09-01');

            expect(time.textContent).toBe('1 Sept 2026');
            expect(time.dateTime).toBe('2026-09-01');
        });
    }

    it('Given_a_date_that_does_not_exist_When_rendered_Then_it_throws', () => {
        // Date.UTC would silently roll 30 February over into 2 March.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);

        expect(() => renderedTime('2026-02-30')).toThrow(/not a calendar date/);
    });

    it('Given_a_value_that_is_not_YYYY_MM_DD_When_rendered_Then_it_throws', () => {
        vi.spyOn(console, 'error').mockImplementation(() => undefined);

        expect(() => renderedTime('01/09/2026')).toThrow(/YYYY-MM-DD/);
    });
});
