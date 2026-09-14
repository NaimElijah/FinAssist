/**
 * The accounting feature's calls to the ledger, and the web client's anti-corruption edge for them
 * (ADR-029).
 *
 * Each function types the response with the generated wire type and maps it, member by member,
 * onto the feature's own type. A generated type stops here: components and hooks see only
 * `types/`. Decimal amounts stay the strings the ledger sent (ADR-028).
 */
import { LEDGER_API, requestJson, sendJson } from '@/shared/api/client';
import type {
    AccountResponse,
    BalanceResponse,
    MoneyDto,
    OpenAccountRequest,
} from '@/shared/api/contracts/ledger/types.gen';

import type { Account, OpenAccountInput } from '../types/account';
import type { Money } from '../types/money';

/** The ledger's code for opening an account under a name already open (ADR-028: 409). */
export const DUPLICATE_ACCOUNT_NAME = 'DUPLICATE_ACCOUNT_NAME';

const ACCOUNTS_URL = `${LEDGER_API}/accounts`;

/** The URL of one account's sub-resource, with the id encoded as a single path segment. */
function accountUrl(accountId: string, subResource: string): string {
    return `${ACCOUNTS_URL}/${encodeURIComponent(accountId)}/${subResource}`;
}

function toAccount(wire: AccountResponse): Account {
    return { id: wire.id, name: wire.name, type: wire.type, currency: wire.currency };
}

function toMoney(wire: MoneyDto): Money {
    return { amount: wire.amount, currency: wire.currency };
}

/**
 * Every account, in the order the ledger lists them, which is by name (ADR-028).
 *
 * @returns the accounts, in the ledger's order.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function listAccounts(): Promise<Account[]> {
    const wire = await requestJson<AccountResponse[]>(ACCOUNTS_URL);
    return wire.map(toAccount);
}

/**
 * Open an account. The ledger generates its id.
 *
 * @param input - the name, type and currency to open it with.
 * @returns the account as opened.
 * @throws ApiError - on a non-2xx response: 409 `DUPLICATE_ACCOUNT_NAME` for a name already open,
 *   400 `MALFORMED_REQUEST`, possibly listing field `errors`, for a request the ledger cannot read.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function openAccount(input: OpenAccountInput): Promise<Account> {
    const body: OpenAccountRequest = {
        name: input.name,
        type: input.type,
        currency: input.currency,
    };
    const wire = await sendJson<AccountResponse>(ACCOUNTS_URL, 'POST', body);
    return toAccount(wire);
}

/**
 * An account's balance, derived by the ledger from its entries. The browser never sums one.
 *
 * @param accountId - the account's id.
 * @returns the balance, in the account's currency, exactly as the ledger computed it.
 * @throws ApiError - on a non-2xx response: 404 `ACCOUNT_NOT_FOUND` for an unknown account.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function getBalance(accountId: string): Promise<Money> {
    const wire = await requestJson<BalanceResponse>(accountUrl(accountId, 'balance'));
    return toMoney(wire.balance);
}
