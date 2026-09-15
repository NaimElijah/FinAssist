// @vitest-environment jsdom
// Queries the rendered table by role, so it needs a DOM.

import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

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

    it('Given_a_table_that_fits_its_region_When_rendered_Then_the_region_is_named_by_its_caption_and_is_not_a_Tab_stop', () => {
        // With nothing to scroll, a Tab stop would be one more key press before the table's own
        // links and buttons, for a scroll affordance nobody can use.
        layOut({ scrollWidth: 300, clientWidth: 300 });

        renderAccounts();

        const region = screen.getByRole('region', { name: 'Accounts' });
        expect(within(region).getByRole('table', { name: 'Accounts' })).toBeDefined();
        expect(region.hasAttribute('tabindex')).toBe(false);
    });

    it('Given_a_table_wider_than_its_region_When_rendered_Then_the_region_is_a_Tab_stop', () => {
        // Focusable, so a keyboard user can scroll a table wider than the screen with arrow keys.
        layOut({ scrollWidth: 900, clientWidth: 300 });

        renderAccounts();

        expect(screen.getByRole('region', { name: 'Accounts' }).tabIndex).toBe(0);
    });

    it('Given_a_table_that_fits_When_the_window_narrows_until_it_overflows_Then_the_region_becomes_a_Tab_stop', () => {
        layOut({ scrollWidth: 300, clientWidth: 300 });
        renderAccounts();
        const region = screen.getByRole('region', { name: 'Accounts' });

        layOut({ scrollWidth: 900, clientWidth: 300 });
        act(() => {
            window.dispatchEvent(new Event('resize'));
        });

        expect(region.tabIndex).toBe(0);
    });

    it('Given_a_visually_hidden_caption_When_rendered_Then_it_still_names_the_scroll_region', () => {
        renderAccounts(true);

        expect(screen.getByRole('region', { name: 'Accounts' })).toBeDefined();
    });

    it('Given_two_tables_When_rendered_Then_each_scroll_region_is_named_by_its_own_caption', () => {
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
