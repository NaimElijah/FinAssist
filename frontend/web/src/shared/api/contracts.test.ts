// @vitest-environment node
/**
 * Type-level proof that generation preserves the wire rules ADR-028 and ADR-029 depend on.
 *
 * Nothing here runs: `expectTypeOf` assertions are checked by `tsc`, so a regenerated file that
 * silently loosens a type - every response member turning optional, a decimal becoming
 * `number` - fails `npm run typecheck`, without needing a value to assert against. Vitest runs no
 * type check of its own, so `npm run test:coverage` passes these assertions as no-ops; the
 * guarantee rests on `typecheck`, which CI runs.
 */
import { describe, expectTypeOf, it } from 'vitest';

import type {
    AccountResponse,
    DefineScenarioRequest,
    MoneyDto,
    Problem as LedgerProblem,
    ScenarioResponse,
    TransactionResponse,
    TransactionStateResponse,
} from './contracts/ledger/types.gen';
import type {
    DispositionModel,
    DisposalRequestModel,
    LotModel,
    MoneyModel,
    PositionModel,
    Problem as AssistantProblem,
    ValuationModel,
} from './contracts/assistant/types.gen';
import type { LogoutResponse, SessionResponse } from './contracts/gateway/types.gen';

describe('generated contract types', () => {
    it('Given_either_services_Problem_When_reading_its_code_Then_it_is_a_string', () => {
        expectTypeOf<LedgerProblem['code']>().toBeString();
        expectTypeOf<AssistantProblem['code']>().toBeString();
    });

    it('Given_a_money_amount_When_read_from_either_document_Then_it_is_a_string_not_a_number', () => {
        expectTypeOf<MoneyDto['amount']>().toBeString();
        expectTypeOf<MoneyModel['amount']>().toBeString();
    });

    it('Given_a_quantity_or_a_growth_rate_When_read_from_either_document_Then_it_is_a_string_not_a_number', () => {
        // ADR-028: every decimal on the wire is a string, not only money - a holding's quantity
        // and a scenario's growth rate per step alike, in a request as much as a response.
        expectTypeOf<DisposalRequestModel['quantity']>().toEqualTypeOf<string>();
        expectTypeOf<DispositionModel['quantity']>().toEqualTypeOf<string>();
        expectTypeOf<LotModel['quantity']>().toEqualTypeOf<string>();
        expectTypeOf<ValuationModel['quantity']>().toEqualTypeOf<string>();
        expectTypeOf<DefineScenarioRequest['growthRatePerStep']>().toEqualTypeOf<string>();
        expectTypeOf<ScenarioResponse['growthRatePerStep']>().toEqualTypeOf<string>();
    });

    it('Given_the_ledgers_always_present_response_members_When_generated_Then_they_are_not_optional', () => {
        // The ledger document marks an always-present response member `required`,
        // so the generated type must drop the member from its optional side, not just keep it
        // present in practice.
        expectTypeOf<AccountResponse['id']>().toEqualTypeOf<string>();
        expectTypeOf<TransactionResponse['id']>().toEqualTypeOf<string>();
    });

    it('Given_a_transactions_lifecycle_date_When_generated_Then_it_stays_optional', () => {
        // `on` is absent until a transition sets it, so the schema never marks it required -
        // the counterexample that proves the assertion above is not vacuously true.
        expectTypeOf<TransactionStateResponse['on']>().toEqualTypeOf<string | undefined>();
    });

    it('Given_a_portfolio_positions_always_present_members_When_generated_Then_they_are_not_optional', () => {
        // The assistant document follows the same rule: a pydantic field with no default is
        // required in FastAPI's schema.
        expectTypeOf<PositionModel['symbol']>().toEqualTypeOf<string>();
        expectTypeOf<PositionModel['quantity']>().toEqualTypeOf<string>();
    });

    it('Given_the_gateways_session_response_When_generated_Then_every_member_is_required', () => {
        // The gateway document marks subject, name and roles required (ADR-030): a session with
        // an absent name would otherwise type-check while UserMenu has nothing to show.
        expectTypeOf<SessionResponse['subject']>().toEqualTypeOf<string>();
        expectTypeOf<SessionResponse['name']>().toEqualTypeOf<string>();
        expectTypeOf<SessionResponse['roles']>().toEqualTypeOf<string[]>();
    });

    it('Given_the_gateways_logout_response_When_generated_Then_logoutUrl_is_required', () => {
        // browserLocation.assign has nothing to navigate to if this were optional.
        expectTypeOf<LogoutResponse['logoutUrl']>().toEqualTypeOf<string>();
    });
});
