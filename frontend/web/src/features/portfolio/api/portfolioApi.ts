/**
 * The portfolio feature's calls to the assistant, and the web client's anti-corruption edge for
 * them (ADR-026, ADR-029).
 *
 * Each function types the response with the generated wire type and maps it, member by member,
 * onto the feature's own type. A generated type stops here: components and hooks see only
 * `types/`. Quantities and amounts stay the strings the assistant sent, signs included - never
 * converted (ADR-028, ADR-029). A currency the contract does not name, or a quantity or amount
 * that is not a decimal string, is refused here with `ApiBodyError`, as the other features refuse
 * theirs.
 */
import { ApiBodyError, ASSISTANT_API, requestJson, sendJson } from '@/shared/api/client';
import type {
    DisposalRequestModel,
    DispositionModel,
    LotModel,
    MoneyModel,
    PositionModel,
    ValuationModel,
} from '@/shared/api/contracts/assistant/types.gen';
import { decimalString } from '@/shared/api/decimal';
import { log } from '@/shared/lib/logger';

import type { DisposalInput, Disposition, Lot } from '../types/disposal';
import type { Position, Valuation } from '../types/holding';
import { CURRENCIES, type Money } from '../types/money';

/** The assistant's code for a symbol under which nothing is held (ADR-028: 404). */
export const HOLDING_NOT_FOUND = 'HOLDING_NOT_FOUND';

/** The assistant's code for a holding no recent enough quote can value (ADR-028: 503). */
export const PRICE_UNAVAILABLE = 'PRICE_UNAVAILABLE';

const HOLDINGS_URL = `${ASSISTANT_API}/portfolio/holdings`;

/**
 * The URL of one read or calculation on a holding, its symbol encoded as a single path segment: a
 * symbol containing a space or a slash would otherwise change the path it names.
 */
function holdingUrl(symbol: string, resource: 'position' | 'valuation' | 'disposal'): string {
    return `${HOLDINGS_URL}/${encodeURIComponent(symbol)}/${resource}`;
}

/**
 * A currency the contract does not name.
 *
 * The generated type promises one of five codes, but the body is unvalidated JSON, so this keeps
 * a runtime default rather than trusting it blindly.
 *
 * @throws ApiBodyError - always.
 */
function unknownCurrency(url: string): never {
    log.error('assistant response has a currency the contract does not name', { url });
    throw new ApiBodyError(url, 'A response arrived with a currency the contract does not name');
}

function toMoney(wire: MoneyModel, url: string): Money {
    if (!CURRENCIES.includes(wire.currency)) {
        return unknownCurrency(url);
    }
    return { amount: decimalString(wire.amount, url, 'amount'), currency: wire.currency };
}

function toPosition(wire: PositionModel, url: string): Position {
    return {
        symbol: wire.symbol,
        quantity: decimalString(wire.quantity, url, 'quantity'),
        cost: toMoney(wire.cost, url),
    };
}

function toValuation(wire: ValuationModel, url: string): Valuation {
    return {
        symbol: wire.symbol,
        quantity: decimalString(wire.quantity, url, 'quantity'),
        cost: toMoney(wire.cost, url),
        unitPrice: toMoney(wire.unitPrice, url),
        marketValue: toMoney(wire.marketValue, url),
        unrealisedGain: toMoney(wire.unrealisedGain, url),
    };
}

function toLot(wire: LotModel, url: string): Lot {
    return {
        acquiredOn: wire.acquiredOn,
        quantity: decimalString(wire.quantity, url, 'quantity'),
        unitCost: toMoney(wire.unitCost, url),
    };
}

function toDisposition(wire: DispositionModel, url: string): Disposition {
    return {
        symbol: wire.symbol,
        quantity: decimalString(wire.quantity, url, 'quantity'),
        proceeds: toMoney(wire.proceeds, url),
        costOfSale: toMoney(wire.costOfSale, url),
        realisedGain: toMoney(wire.realisedGain, url),
        remaining: wire.remaining.map((lot) => toLot(lot, url)),
    };
}

/**
 * What is held under one symbol, across its lots, and what it cost in total.
 *
 * @param symbol - the symbol as the owner typed it; the assistant upper-cases it.
 * @returns the position, its quantity and cost exactly as the assistant sent them.
 * @throws ApiError - on a non-2xx response: 404 `HOLDING_NOT_FOUND` when nothing is held.
 * @throws ApiBodyError - when the response carries no JSON body, a currency the contract does
 *   not name, or a quantity or amount that is not a decimal string.
 */
export async function readPosition(symbol: string): Promise<Position> {
    const url = holdingUrl(symbol, 'position');
    const wire = await requestJson<PositionModel>(url);
    return toPosition(wire, url);
}

/**
 * One holding valued at the latest usable unit price, and the gain that price implies.
 *
 * @param symbol - the symbol as the owner typed it; the assistant upper-cases it.
 * @returns the valuation, every figure exactly as the assistant sent it - an unrealised loss as a
 *   negative amount.
 * @throws ApiError - on a non-2xx response: 404 `HOLDING_NOT_FOUND` when nothing is held, 503
 *   `PRICE_UNAVAILABLE` when no quote is recent enough to value it.
 * @throws ApiBodyError - when the response carries no JSON body, a currency the contract does
 *   not name, or a quantity or amount that is not a decimal string.
 */
export async function valueHolding(symbol: string): Promise<Valuation> {
    const url = holdingUrl(symbol, 'valuation');
    const wire = await requestJson<ValuationModel>(url);
    return toValuation(wire, url);
}

/**
 * What selling a quantity of one holding's units for the given proceeds would realise, its lots
 * consumed oldest first (ADR-013). A calculation that records nothing: no lot is consumed, so it
 * is safe to repeat.
 *
 * A `POST` with a JSON body only so the proceeds, an amount of money, never travel in a URL
 * (ADR-028), so no query string is built.
 *
 * @param symbol - the symbol as the owner typed it; the assistant upper-cases it.
 * @param input - the quantity and the proceeds, sent exactly as typed.
 * @returns the disposition, every figure exactly as the assistant sent it - a realised loss as a
 *   negative amount - and the lots that would remain, oldest first, in the order received.
 * @throws ApiError - on a non-2xx response: 404 `HOLDING_NOT_FOUND`; 422 `INSUFFICIENT_HOLDING`
 *   for more units than are held; 422 `RULE_VIOLATED` for a non-positive quantity or an amount
 *   finer than its currency; 422 `CURRENCY_MISMATCH` for proceeds in a currency other than the
 *   lots'; 400 `MALFORMED_REQUEST`, listing field `errors`.
 * @throws ApiBodyError - when the response carries no JSON body, a currency the contract does
 *   not name, or a quantity or amount that is not a decimal string.
 */
export async function priceDisposal(symbol: string, input: DisposalInput): Promise<Disposition> {
    const url = holdingUrl(symbol, 'disposal');
    const body: DisposalRequestModel = {
        quantity: input.quantity,
        proceeds: { amount: input.proceeds.amount, currency: input.proceeds.currency },
    };
    const wire = await sendJson<DispositionModel>(url, 'POST', body);
    return toDisposition(wire, url);
}
