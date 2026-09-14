// @vitest-environment jsdom
// Queries the rendered table by role, so it needs a DOM.

import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

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

describe('DataTable', () => {
    it('Given_a_caption_When_rendered_Then_it_names_the_table', () => {
        renderAccounts();

        expect(screen.getByRole('table', { name: 'Accounts' })).toBeDefined();
    });

    it('Given_a_visually_hidden_caption_When_rendered_Then_it_still_names_the_table', () => {
        renderAccounts(true);

        expect(screen.getByRole('table', { name: 'Accounts' })).toBeDefined();
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
