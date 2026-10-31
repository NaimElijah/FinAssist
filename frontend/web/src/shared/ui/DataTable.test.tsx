// @vitest-environment jsdom
// Queries the rendered table by role, so it needs a DOM.

import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { isObserved, resizeElement } from '@/shared/testing/fakeResizeObserver';

import { type Column, DataTable } from './DataTable';

interface AccountRow {
    readonly id: string;
    readonly name: string;
    readonly balance: string;
}

const COLUMNS: readonly Column<AccountRow>[] = [
    { key: 'name', header: 'Account', render: (row) => row.name },
    { key: 'balance', header: 'Balance', align: 'end', render: (row) => row.balance },
];

const ROWS: readonly AccountRow[] = [
    { id: 'acc-1', name: 'Checking', balance: '1234.50' },
    { id: 'acc-2', name: 'Savings', balance: '-12.00' },
];

function renderAccounts(captionHidden = false) {
    return render(
        <DataTable
            caption="Accounts"
            captionHidden={captionHidden}
            columns={COLUMNS}
            rows={ROWS}
            rowKey={(row) => row.id}
        />,
    );
}

/**
 * Make every element report the given widths, as a laid-out browser would. jsdom lays nothing out,
 * so both read 0 and nothing ever overflows.
 */
function layOut({ scrollWidth, clientWidth }: { scrollWidth: number; clientWidth: number }) {
    vi.spyOn(Element.prototype, 'scrollWidth', 'get').mockReturnValue(scrollWidth);
    vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(clientWidth);
}

/**
 * The element the table scrolls inside. Found through the table, since it is a landmark only while
 * the table overflows it.
 */
function scrollContainer(): HTMLElement {
    const container = screen.getByRole('table', { name: 'Accounts' }).parentElement;
    if (container === null) {
        throw new Error('the table has no scroll container');
    }
    return container;
}

afterEach(() => {
    vi.restoreAllMocks();
});

describe('DataTable', () => {
    it('Given_a_caption_When_rendered_Then_it_names_the_table', () => {
        renderAccounts();

        expect(screen.getByRole('table', { name: 'Accounts' })).toBeDefined();
    });

    it('Given_a_visually_hidden_caption_When_rendered_Then_it_still_names_the_table', () => {
        renderAccounts(true);

        expect(screen.getByRole('table', { name: 'Accounts' })).toBeDefined();
    });

    it('Given_a_table_that_fits_its_container_When_rendered_Then_it_is_neither_a_region_nor_a_Tab_stop', () => {
        // With nothing to scroll, a Tab stop would be one more key press before the table's own
        // links and buttons, and a landmark one more stop, named like the table, before it.
        layOut({ scrollWidth: 300, clientWidth: 300 });

        renderAccounts();

        expect(screen.queryByRole('region')).toBeNull();
        expect(scrollContainer().hasAttribute('tabindex')).toBe(false);
        expect(screen.getByRole('table', { name: 'Accounts' })).toBeDefined();
    });

    it('Given_a_table_wider_than_its_container_When_rendered_Then_it_is_a_region_named_by_its_caption_and_a_Tab_stop', () => {
        // Focusable, so a keyboard user can scroll a table wider than the screen with arrow keys,
        // and named, so what they are scrolling is announced.
        layOut({ scrollWidth: 900, clientWidth: 300 });

        renderAccounts();

        const region = screen.getByRole('region', { name: 'Accounts' });
        expect(region.tabIndex).toBe(0);
        expect(within(region).getByRole('table', { name: 'Accounts' })).toBeDefined();
    });

    it('Given_a_table_that_fits_When_its_container_narrows_until_it_overflows_Then_it_becomes_a_named_region_and_a_Tab_stop', () => {
        // A narrower window, a zoom, or a sibling taking room all reach the table as its container
        // shrinking.
        layOut({ scrollWidth: 300, clientWidth: 300 });
        renderAccounts();
        const container = scrollContainer();

        layOut({ scrollWidth: 900, clientWidth: 300 });
        act(() => {
            resizeElement(container);
        });

        expect(screen.getByRole('region', { name: 'Accounts' })).toBe(container);
        expect(container.tabIndex).toBe(0);
    });

    it('Given_a_table_that_fits_When_its_content_reflows_wider_without_a_window_resize_Then_the_region_becomes_a_Tab_stop', () => {
        // A web font finishing loading widens the columns: the table grows inside a region whose
        // own size is unchanged, and no window resize is fired.
        layOut({ scrollWidth: 300, clientWidth: 300 });
        renderAccounts();
        const container = scrollContainer();

        layOut({ scrollWidth: 900, clientWidth: 300 });
        act(() => {
            resizeElement(screen.getByRole('table', { name: 'Accounts' }));
        });

        expect(screen.getByRole('region', { name: 'Accounts' })).toBe(container);
        expect(container.tabIndex).toBe(0);
    });

    it('Given_a_table_that_overflows_When_its_container_widens_until_it_fits_Then_it_is_no_longer_a_region_or_a_Tab_stop', () => {
        layOut({ scrollWidth: 900, clientWidth: 300 });
        renderAccounts();
        const region = screen.getByRole('region', { name: 'Accounts' });

        layOut({ scrollWidth: 900, clientWidth: 900 });
        act(() => {
            resizeElement(region);
        });

        expect(screen.queryByRole('region')).toBeNull();
        expect(region.hasAttribute('tabindex')).toBe(false);
    });

    it('Given_a_rendered_table_When_it_unmounts_Then_neither_the_region_nor_the_table_is_observed', () => {
        const { unmount } = renderAccounts();
        const container = scrollContainer();
        const table = screen.getByRole('table', { name: 'Accounts' });
        expect([isObserved(container), isObserved(table)]).toEqual([true, true]);

        unmount();

        expect(isObserved(container)).toBe(false);
        expect(isObserved(table)).toBe(false);
    });

    it('Given_a_visually_hidden_caption_When_the_table_overflows_Then_it_still_names_the_scroll_region', () => {
        layOut({ scrollWidth: 900, clientWidth: 300 });
        renderAccounts(true);

        expect(screen.getByRole('region', { name: 'Accounts' })).toBeDefined();
    });

    it('Given_two_tables_that_overflow_When_rendered_Then_each_scroll_region_is_named_by_its_own_caption', () => {
        layOut({ scrollWidth: 900, clientWidth: 300 });
        render(
            <>
                <DataTable
                    caption="Accounts"
                    columns={COLUMNS}
                    rows={ROWS}
                    rowKey={(row) => row.id}
                />
                <DataTable
                    caption="Closed accounts"
                    columns={COLUMNS}
                    rows={[]}
                    rowKey={(row) => row.id}
                />
            </>,
        );

        const [firstId, secondId] = screen
            .getAllByRole('region')
            .map((region) => region.getAttribute('aria-labelledby'));
        expect(firstId).not.toBe(secondId);
        expect(screen.getByRole('region', { name: 'Accounts' })).not.toBe(
            screen.getByRole('region', { name: 'Closed accounts' }),
        );
    });

    it('Given_columns_When_rendered_Then_their_headers_are_column_headers_in_order', () => {
        renderAccounts();

        expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
            'Account',
            'Balance',
        ]);
    });

    it('Given_rows_When_rendered_Then_each_row_renders_one_cell_per_column', () => {
        renderAccounts();

        const [, ...bodyRows] = screen.getAllByRole('row');
        expect(
            bodyRows.map((row) =>
                within(row)
                    .getAllByRole('cell')
                    .map((cell) => cell.textContent),
            ),
        ).toEqual([
            ['Checking', '1234.50'],
            ['Savings', '-12.00'],
        ]);
    });

    it('Given_a_row_header_column_When_rendered_Then_its_cells_are_row_headers_scoped_to_their_row', () => {
        const columns: readonly Column<AccountRow>[] = [
            { key: 'name', header: 'Account', rowHeader: true, render: (row) => row.name },
            { key: 'balance', header: 'Balance', align: 'end', render: (row) => row.balance },
        ];
        render(
            <DataTable caption="Accounts" columns={columns} rows={ROWS} rowKey={(row) => row.id} />,
        );

        const rowHeaders = screen.getAllByRole('rowheader');
        expect(rowHeaders.map((header) => header.textContent)).toEqual(['Checking', 'Savings']);
        expect(rowHeaders.map((header) => header.getAttribute('scope'))).toEqual(['row', 'row']);
        expect(rowHeaders.map((header) => header.getAttribute('data-align'))).toEqual([
            'start',
            'start',
        ]);
        expect(screen.getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
            '1234.50',
            '-12.00',
        ]);
    });

    it('Given_no_row_header_column_When_rendered_Then_no_cell_is_a_row_header', () => {
        renderAccounts();

        expect(screen.queryAllByRole('rowheader')).toEqual([]);
    });

    it('Given_an_end_aligned_money_column_When_rendered_Then_its_header_and_cells_carry_the_end_alignment_hook', () => {
        renderAccounts();

        const balanceHeader = screen.getByRole('columnheader', { name: 'Balance' });
        const balanceCells = screen.getAllByRole('cell').filter((_, index) => index % 2 === 1);
        expect(balanceHeader.getAttribute('data-align')).toBe('end');
        expect(balanceCells.map((cell) => cell.getAttribute('data-align'))).toEqual(['end', 'end']);
    });

    it('Given_a_column_with_no_alignment_When_rendered_Then_it_carries_the_start_alignment_hook', () => {
        renderAccounts();

        const nameHeader = screen.getByRole('columnheader', { name: 'Account' });
        const nameCells = screen.getAllByRole('cell').filter((_, index) => index % 2 === 0);
        expect(nameHeader.getAttribute('data-align')).toBe('start');
        expect(nameCells.map((cell) => cell.getAttribute('data-align'))).toEqual([
            'start',
            'start',
        ]);
    });
});
