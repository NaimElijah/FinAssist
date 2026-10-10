# FinAssist — requirements

What the system must do. *How* it is built is [architecture](../architecture/architecture.md);
*why* a given solution was chosen is [the ADRs](../ADRs/).

> **Status: the domain and application layers are written, the ledger's store is PostgreSQL, and
> the ledger and the assistant each have a transport behind a sign-in; the web client has its
> accounting, budgeting and planning areas over the ledger's, and its portfolio area over the
> assistant's.**
> The store is [ADR-006](../ADRs/ADR-006-postgresql-for-the-ledger-store.md) and
> [ADR-024](../ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md). The transport's decisions
> are recorded in [ADR-026](../ADRs/ADR-026-web-client-calls-both-services-through-one-origin.md)
> and [ADR-028](../ADRs/ADR-028-http-wire-conventions.md); the ledger implements them, serving
> accounting, budgeting and planning under `/api/v1`, with its generated OpenAPI document pinned
> by `LedgerContractIT`. The assistant implements them too, serving `portfolio` under `/api/v1`,
> with its generated OpenAPI document pinned by `tests/test_portfolio_api.py` and
> `tests/test_contract.py`; its portfolio data still comes from nowhere real — an opt-in
> development seed aside — because neither a ledger client nor a price feed exists. The web
> client calls the ledger from its accounting, budgeting and planning screens, and the assistant
> from its portfolio screens, every call through the gateway. Authentication and authorisation
> are decided and enforced: [ADR-030](../ADRs/ADR-030-oidc-authentication-through-a-bff-gateway.md)
> supersedes ADR-027, while [ADR-031](../ADRs/ADR-031-keycloak-as-the-identity-provider.md) and
> [ADR-032](../ADRs/ADR-032-role-based-authorization-per-service.md) extend it. The gateway signs
> the browser in and keeps its tokens server-side; the ledger validates every bearer token and
> denies by default, through `LedgerSecurityConfiguration`; the assistant does the same, through
> `BearerAuthentication` and `RequirePermission`; and every journal row written from `V1_2` onward
> names who wrote it ([ADR-033](../ADRs/ADR-033-the-journal-records-who.md)). Requirements marked
> **Enforced** name the class and the test that hold them today, so this document can be checked
> rather than trusted. Everything still marked `TODO` is a decision waiting on its owner, not an
> omission to be filled in by inference — behaviour found in code is evidence of a requirement,
> never proof of one.

## Purpose and scope

FinAssist is a personal finance system for a single owner managing their own money. It
covers four capability areas, confirmed as all in scope:

1. **Tracking** — accounts, transactions, categorisation and budgets.
2. **Assistance** — natural-language questions answered over the owner's own financial
   data, backed by an LLM.
3. **Analysis** — portfolio and market analysis over holdings.
4. **Planning** — planned purchases, projected balances over time, and comparison of
   financial strategies.

These are reached through a **web client**, which is in scope. It is the only interface;
there is no CLI and no mobile client.

The four capability areas are realised as six bounded contexts, listed with their owning
service in [ADR-010](../ADRs/ADR-010-bounded-context-map.md). Which capability a requirement
belongs to, and which context enforces it, should agree — where they do not, one of the two is
wrong.

## Functional requirements

Each requirement is stated as something a user or caller can do, and names the test or class
that enforces it once one exists.

### Tracking

The ledger keeps books by **double entry** ([ADR-011](../ADRs/ADR-011-double-entry-bookkeeping.md)).

- Open an account with a name, one of five accounting types, and exactly one currency.
  `Account`. Reachable over HTTP as `POST /api/v1/accounts`; `AccountingHttpIT`
- Record a transaction as two or more entries that sum to zero. An unbalanced set cannot be
  recorded. `Transaction`, `TransactionTest`; `RecordTransactionUseCase`,
  `TransactionServiceTest`. Reachable over HTTP as `POST /api/v1/transactions`; `AccountingHttpIT`
- Move a transaction through its lifecycle: clear it once the institution confirms it,
  reconcile it once it is matched to a statement, or void it with a reason.
  `TransactionState`, `TransactionTest`. Reachable over HTTP as `POST
  /api/v1/transactions/{id}/clear`, `/reconcile` and `/void`; `AccountingHttpIT`
- Read the balance of an account, derived from its entries. `Balance`, `BalanceTest`;
  `ViewBalanceUseCase`, `JournalServiceTest`. Reachable over HTTP as `GET
  /api/v1/accounts/{id}/balance`; `AccountingHttpIT`
- Define a budget for a period, with one spending limit per category. `Budget`, `BudgetTest`.
  Reachable over HTTP as `POST /api/v1/budgets`; `BudgetingHttpIT`
- See what remains in a budgeted category once spend is taken off, including when overspent.
  `Budget.remaining`, `BudgetTest`; `ReviewBudgetUseCase`, `BudgetReviewServiceTest`. Spend
  that matched a rule for an unbudgeted category, and spend that matched no rule at all, are
  reported separately rather than folded away — the second is what says the rules need work
  rather than the spending. Reachable over HTTP as `GET /api/v1/budgets/{id}/review`;
  `BudgetingHttpIT`
- Assign a category to a transaction by matching its description. `CategorisationRule`;
  `AddCategorisationRuleUseCase`. Rules are tried in the order they were added and the first
  match wins, which `CategorisationRuleRepository` makes part of its contract. Reachable over
  HTTP as `POST /api/v1/categorisation-rules`; `BudgetingHttpIT`

**Manual entry is the first source of transactions**, chosen by the owner on 2026-09-14: a
transaction is recorded by hand, through the web client. File import and institution sync remain
open; see [Open questions](#open-questions).

### Assistance

One rule holds already ([ADR-014](../ADRs/ADR-014-grounded-answers-require-citations.md)):

- Any claim in an answer that states a figure must cite the ledger record it came from. An
  answer containing an uncited figure is rejected as `Ungrounded`, naming the offending claims.
  `ground`, `test_grounding.py`

TODO (@NaimElijah) — the rest. Expected to cover at least: asking a question in natural
language, and seeing the evidence an answer drew on. Both depend on the privacy question below
being answered first.

### Analysis

Holdings are tracked as **lots with a FIFO cost basis**
([ADR-013](../ADRs/ADR-013-fifo-lot-based-cost-basis.md)).

- Record a purchase as a lot: quantity, unit cost, acquisition date. `Lot`
- Read a position — total quantity and total cost — across the lots of one instrument.
  `position`, `test_portfolio.py`. Reachable over HTTP as `GET
  /api/v1/portfolio/holdings/{symbol}/position`; `test_portfolio_api.py`
- Dispose of units, oldest lots first, and read what those units cost. Selling more than is
  held is refused. `consume_fifo`, `InsufficientHoldingError`; `DisposeUnits`,
  `test_portfolio_usecases.py`. **"Oldest first" is the use case's guarantee, not the domain
  function's**: `consume_fifo` consumes lots in the order it is given them, so `DisposeUnits`
  sorts by acquisition date before calling it. Reachable over HTTP as `POST
  /api/v1/portfolio/holdings/{symbol}/disposal`, a JSON body rather than query parameters
  because the proceeds are an amount of money; `test_portfolio_api.py`
- Read realised gain on a sale, and unrealised gain and market value at a given unit price.
  `realised_gain`, `unrealised_gain`, `market_value`. The valuation is reachable over HTTP as
  `GET /api/v1/portfolio/holdings/{symbol}/valuation`; `test_portfolio_api.py`
- Judge whether a market quote is too old to use, against a supplied clock. `is_stale`;
  `LatestQuote`, `test_marketdata_usecases.py`, which refuses a stale quote rather than
  returning one a caller has to remember to check.

**Not yet decided:** where prices come from. The `marketdata` context has the shape a feed must
be translated into and no client behind it. An opt-in development seed
(`ASSISTANT_DEV_SEED`) loads a small set of fictional lots and quotes so the endpoints above
have something to answer by hand; it is a development aid, not an answer to this question, and
it is off by default.

### Planning

Plans are kept apart from the books: a planned purchase is an intention, not a fact, and the two
are never summed ([ADR-015](../ADRs/ADR-015-planning-as-a-sixth-bounded-context.md)).

- Record a planned flow — a positive amount, a direction (money in, money out, or into
  investments), the date it starts, and how it repeats. `PlannedFlow`, `PlannedFlowTest`;
  `PlanFlowUseCase`, `PlanServiceTest`. Reachable over HTTP as `POST /api/v1/planned-flows`;
  `PlanningHttpIT`
  ([#10](https://github.com/NaimElijah/FinAssist/issues/10))
- Repeat a flow weekly, monthly or yearly, ending on a date or after a number of occurrences.
  `Recurrence`, `RecurrenceTest`
- Move a planned flow through its lifecycle: commit to it, mark it realised once the money has
  moved, or cancel it with a reason. `PlanState`, `PlannedFlowTest`; `TransitionPlanUseCase`,
  `PlanServiceTest`. Reachable over HTTP as `POST /api/v1/planned-flows/{id}/commit`,
  `/realise` and `/cancel`; `PlanningHttpIT`
- Define a named strategy — a set of planned flows plus an assumption about investment growth —
  including the do-nothing baseline, which has no flows at all. `Scenario`, `ScenarioTest`.
  Reachable over HTTP as `POST /api/v1/scenarios`; `PlanningHttpIT`
  ([#12](https://github.com/NaimElijah/FinAssist/issues/12))
- Read a projected timeline of cash and invested balances across a horizon, derived from an
  opening balance and a strategy. `Projection.of`, `ProjectionTest`; `ProjectScenarioUseCase`,
  `ForecastServiceTest`. **The caller names which accounts open the cash line and which open
  the invested line** — nothing in FinAssist decides that, and inventing a rule for it would
  bury a policy nobody chose
  ([#11](https://github.com/NaimElijah/FinAssist/issues/11)). Reachable over HTTP as `GET
  /api/v1/scenarios/{id}/projection`; `PlanningHttpIT`
- See the first **step boundary** at which the projected cash balance is negative, or that none
  is. `Projection.firstShortfall`, `ProjectionTest`. A dip that opens and closes inside a single
  step is not reported — a large payment covered by a salary later the same month looks
  untroubled at a monthly cadence. Projecting at a finer cadence narrows the window; nothing
  closes it.
- Compare two or more strategies over one horizon and read how far each diverges from a chosen
  baseline, step by step and at the end. `Comparison`, `ComparisonTest`. Reachable over HTTP as
  `GET /api/v1/scenario-comparisons`; `PlanningHttpIT`
  ([#12](https://github.com/NaimElijah/FinAssist/issues/12))

**A projection covers one currency, and this is a limitation rather than an oversight.** An owner
with an ILS account and a USD account gets two timelines and no combined figure, because FinAssist
holds no exchange rates ([ADR-012](../ADRs/ADR-012-multi-currency-without-conversion.md)).
Producing a single net-worth line is the trigger for revisiting that decision, not something to
work around here.

**Projections are deterministic**
([ADR-016](../ADRs/ADR-016-deterministic-per-currency-projection.md)): stated assumptions in, one
reproducible line out. There is no probabilistic simulation, and a single line understates
uncertainty by construction.

A plan is stored in the ledger's `planning` schema
([ADR-024](../ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md)), and drawing one is the
[web client](#web-client)'s concern: the domain computes the series and draws nothing. A projection
re-reads its flows before computing, so a plan cancelled after its strategy was named stops being
forecast (`ForecastService`); without that, `countsTowardsProjection` would be unreachable in
practice.

### Web client

The owner enumerated the web client on 2026-09-14. **Every capability listed below is
implemented**, each naming the screen that implements it and the tests that enforce it; only "ask a
question" stays excluded, as stated below. How the client reaches the two services is
[ADR-026](../ADRs/ADR-026-web-client-calls-both-services-through-one-origin.md), what it is
built with is [ADR-029](../ADRs/ADR-029-web-client-stack.md), and how it signs in is
[ADR-030](../ADRs/ADR-030-oidc-authentication-through-a-bff-gateway.md).

Through the web client, the owner can:

- Sign in, see who is signed in - their name, and a "Read-only" badge for a `viewer` - and sign
  out. Every screen requires a session; a 401 from any other read or write is what notices the
  gateway's own session ending mid-visit, not a poll of its own. `SessionGate`, `SignIn`,
  `UserMenu`; `SessionGate.test.tsx`, `SignIn.test.tsx`, `UserMenu.test.tsx`,
  `sessionApi.test.ts`, `browserLocation.test.ts`, `queryClient.test.ts`
- Open an account, and see every account with its derived balance. `AccountsPage`,
  `OpenAccountForm`; `AccountsPage.test.tsx`, `accountingApi.test.ts`
- Record a transaction by hand. `RecordTransactionPage`; `RecordTransactionPage.test.tsx`,
  `accountingApi.test.ts`, `transaction.test.ts`
- See an account's transactions with their lifecycle state. `AccountDetailPage`;
  `AccountDetailPage.test.tsx`, `accountingApi.test.ts`
- Clear, reconcile or void a transaction. `AccountDetailPage`; `AccountDetailPage.test.tsx`,
  `accountingApi.test.ts`
- Define a budget for a month with a limit per category, and see every budget with its period,
  currency and total. `BudgetsPage`, `DefineBudgetForm`; `BudgetsPage.test.tsx`,
  `budgetingApi.test.ts`, `period.test.ts`
- Review spend against a budget: what is overspent, what is unbudgeted, and what is
  uncategorised. `BudgetReviewPage`; `BudgetReviewPage.test.tsx`, `budgetingApi.test.ts`
- Add categorisation rules, in order. `CategorisationRulesPage`, `AddCategorisationRuleForm`;
  `CategorisationRulesPage.test.tsx`, `budgetingApi.test.ts`
- Plan a flow, and move it through its lifecycle. `PlannedFlowsPage`, `PlanFlowForm`;
  `PlannedFlowsPage.test.tsx`, `planningApi.test.ts`, `recurrence.test.ts`
- Define a scenario. `ScenariosPage`, `DefineScenarioForm`; `ScenariosPage.test.tsx`,
  `planningApi.test.ts`
- Chart a scenario's projection against chosen cash and invested accounts, with the first
  shortfall marked. `ProjectionPage`, `ProjectionChart`, `PlanningAccountsBridge`;
  `ProjectionPage.test.tsx`, `ProjectionChart.test.tsx`, `chartScale.test.ts`,
  `PlanningAccountsBridge.test.tsx`, `accountOptions.test.tsx`, `planningApi.test.ts`
- Compare scenarios against a baseline. `ComparisonPage`; `ComparisonPage.test.tsx`,
  `planningApi.test.ts`
- Look up a holding by symbol. `PortfolioPage`, `SymbolLookup`; `PortfolioPage.test.tsx`,
  `HoldingPage.test.tsx`
- See a holding's position — its quantity and total cost — or that nothing is held under the
  symbol. `HoldingPage`; `HoldingPage.test.tsx`, `portfolioApi.test.ts`
- See a holding's valuation — its unit price, market value and unrealised gain or loss — or that no
  usable price exists right now, while the position still shows. `HoldingPage`;
  `HoldingPage.test.tsx`, `portfolioApi.test.ts`
- Price a disposal as a calculation that records nothing: its cost of sale, its realised gain or
  loss, and the lots that would remain, oldest first. `DisposalSection`;
  `DisposalSection.test.tsx`, `portfolioApi.test.ts`

**A viewer sees every area and is offered no control that changes data.** This is a client-side
courtesy alone, never the enforcement: the ledger and the assistant decide access from the token,
regardless of what the browser renders (see Authorization, under
[Non-functional requirements](#non-functional-requirements)). `useWriteAccess`,
`writeAccessFor`; `writeAccess.test.tsx`, `access.test.ts`, and one read-only test per screen
above. The one exception is pricing a disposal (`DisposalSection`), which stays offered to a
viewer because it is a read, not a write.

**One screen area per user-facing context**: accounts and transactions, budgets, planning, and
portfolio. `marketdata` has no surface of its own and is visible only through portfolio figures.

**"Ask a question" is deliberately excluded** until the privacy question in
[Open questions](#open-questions) is answered. An assistance screen needs an LLM adapter behind it,
and wiring one would answer that question by implication.

**The portfolio screens have no real data source yet.** No service records a lot, and no price feed
exists; where prices come from is an [open question](#open-questions). Until then, nothing held and
no usable price are the ordinary answers, so the screens show both as calm states rather than as
errors. The assistant's opt-in development seed ([Analysis](#analysis)) is the only way to see
figures by hand.

## Business rules and invariants

These are the constraints the domain must enforce, not suggestions the UI should follow.
Each row names the code and the tests that enforce it, so it can be checked rather than trusted;
where enforcement is partial, or rests on a convention rather than a test, its status says so.

| Rule | Enforced by | Status |
|---|---|---|
| A transaction's entries must sum to zero, in one currency, with at least two of them | `Transaction` constructor; `TransactionTest` | **Enforced** |
| Money is exact decimal — never a floating-point type | `Money` (Java and Python); `LedgerArchitectureTest.money_is_never_floating_point`; `test_architecture.py::…Then_it_names_no_float`; `MoneyTest`, `test_money.py` | **Enforced** |
| Amounts in two currencies are never combined | `Money.plus/minus/compareTo`; `CurrencyMismatchException` | **Enforced** |
| An amount more precise than its currency admits is rejected, not rounded | `Money` constructor; `MoneyTest`, `test_money.py` | **Enforced** |
| Account balance is derived, never set directly | `Balance.of`; `Account` has no balance field; `BalanceTest` | **Enforced** |
| A voided transaction keeps its entries but does not count towards a balance | `Transaction.countsTowardsBalance`; `BalanceTest` | **Enforced** |
| Legal transaction state transitions; a reconciled transaction is frozen | Sealed `TransactionState`; `IllegalTransitionException`; `TransactionTest` | **Enforced** |
| A transaction moves forward in time: it cannot clear before it occurred, reconcile before it cleared, or be voided before either | `Transaction.clear/reconcile/markVoided`; `TransactionTest` | **Enforced** |
| An account has exactly one currency, and a transaction's entries are all in one currency | `Account`; `Transaction` constructor | **Enforced** |
| An entry's currency matches the currency of the account it is posted to | `TransactionService.record`; `EntryCurrencyMismatchException`; `TransactionServiceTest` | **Enforced** — needed a repository, as this row predicted, and landed with the application layer (ADR-021) |
| A budget has one limit per category, all in the budget's currency, each positive | `Budget` and `CategoryLimit` constructors; `BudgetTest` | **Enforced** |
| A budget stores no spend; remaining is derived | `Budget.remaining` takes spend as an argument; `BudgetTest` | **Enforced** |
| Disposing of more units than are held is refused | `consume_fifo`; `InsufficientHoldingError`; `test_portfolio.py` | **Enforced** |
| A claim stating a figure must cite the evidence it came from | `ground`; `test_grounding.py`; `AnswerQuestion`, `test_assistance_usecases.py` | **Enforced** — the domain checks a citation exists, and the use case checks it names evidence the answer was actually given, which is the only place holding both. Whether the cited record *supports* the figure is still unchecked |
| A market quote carries an unambiguous instant, and staleness is judged against a supplied clock | `Quote`, `is_stale`; `test_marketdata.py` | **Enforced** |
| A planned flow is for a positive amount; the direction carries the sign | `PlannedFlow` constructor; `PlannedFlowTest` | **Enforced** |
| Legal plan state transitions; a realised plan is frozen and cannot be edited or cancelled | Sealed `PlanState`; `IllegalPlanTransitionException`; `PlannedFlowTest` | **Enforced** |
| A realised or cancelled plan is kept as history but excluded from every projection | `PlannedFlow.countsTowardsProjection`; `ProjectionTest` | **Enforced** |
| Every flow in a strategy is in the strategy's currency | `Scenario` constructor; `ScenarioTest` | **Enforced** |
| A projection is derived from its strategy and opening balance, never stored | `Projection.of`; no settable point; `ProjectionTest` | **Enforced** |
| A projection covers one currency, and its horizon is a whole number of steps | `Projection.of`; `BalancePoint`; `ProjectionTest` | **Enforced** |
| A comparison refuses strategies in different currencies, horizons or step cadences | `Comparison.of`; `ComparisonTest` | **Enforced** |
| A strategy cannot list the same planned flow twice | `Scenario` constructor; `ScenarioTest` | **Enforced** |
| Two strategies in one comparison cannot share a name | `Comparison` constructor; `ComparisonTest` | **Enforced** |
| A recurrence that would end before it begins is refused, not answered with nothing | `Recurrence.Until`; `PlannedFlow` constructor; `RecurrenceTest` | **Enforced** |
| No bounded context depends on another | `LedgerArchitectureTest.contexts_do_not_depend_on_each_other`; `test_architecture.py`; `architecture.test.ts` | **Enforced** |
| Where two contexts must collaborate, they do so through a port the consumer owns and a translation outside both | `SpendJournal`, `OpeningPositionProvider`; `AccountingSpendJournal`, `AccountingOpeningPositions`; `LedgerCompositionIT`; `test_wiring.py` | **Enforced** — the ports name no foreign type, and the translations live in a composition root nothing may import back into ([ADR-022](../ADRs/ADR-022-ledger-composition-root.md)) |
| The application layer is wired by constructor, never by a container | `LedgerArchitectureTest.the_application_layer_depends_on_no_framework`; `test_architecture.py::…Then_it_imports_no_framework` | **Enforced** — a promise `LedgerApplication` had made since the scaffold with nothing checking it |
| A recorded journal entry is never updated or deleted | `V1_1__accounting_schema.sql` — a `BEFORE UPDATE OR DELETE` trigger on all three journal tables; `JournalIsAppendOnlyIT` | **Enforced by the database.** The port still exposes no delete, but the guarantee no longer depends on that: the trigger refuses the statement whoever issues it, and a lifecycle change is an `INSERT` into `journal_transaction_state` ([ADR-024](../ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md)) |
| A domain function is given the current time rather than reading it | `test_architecture.py::…Then_it_reads_no_clock`, which matches an uncalled `datetime.now` reference too; `is_stale`, `Projection.of`, `Transaction.clear` all take a date | **Enforced** on the Python side; a convention in the ledger |
| The currency table is the same in both languages | `CurrencyTest`, `test_currency.py` | **Enforced** |
| The ledger is the sole writer of ledger data; the assistant reads only through its API | [ADR-004](../ADRs/ADR-004-http-openapi-service-contract.md); the ledger's published contract is `contracts/ledger-v1.openapi.json`, pinned by `LedgerContractIT` | Accepted; the ledger's side is built and its contract published, but the assistant has no ledger client yet, so nothing exercises the API from the assistant's side. **The ledger already refuses the assistant's writes**: [ADR-032](../ADRs/ADR-032-role-based-authorization-per-service.md) gives the `assistant` role `ledger:read` alone, and `LedgerPermissions` grants exactly that; `LedgerAuthorizationIT.Given_a_viewer_or_assistant_token_When_a_write_is_requested_Then_it_is_403`, `LedgerPermissionsTest` |
| An account name is unique within the chart of accounts, ignoring surrounding whitespace and case | `account_normalised_name_key` (atomically); `AccountService.open` (for the message); `DuplicateAccountNameException`; `JdbcAccountRepositoryIT`, `AccountServiceTest` | **Enforced** — the repository claims the normalised name, because a check followed by a write is not a rule: two callers can pass the check together. The typed form is what is stored and displayed |
| Every endpoint requires a permission; an unclassified endpoint fails the build | Ledger: `LedgerSecurityConfiguration`'s URL rules — `ledger:read` for `GET /api/v1/**`, `ledger:write` for `POST /api/v1/**`, `denyAll` for anything else; `LedgerAuthorizationIT.Given_the_running_ledger_When_its_handler_mappings_are_read_Then_they_equal_the_named_inventory`. Assistant: one `Depends(require(READ_PORTFOLIO))` per route; `test_authorization.py::Given_the_portfolio_router_When_routes_are_inspected_Then_each_declares_one_permission`. Gateway: no such test | **Enforced on the ledger and the assistant**, within stated limits ([ADR-032](../ADRs/ADR-032-role-based-authorization-per-service.md)). The ledger's test compares every endpoint its request mappings hold with a named inventory, so one added without a URL rule fails the build; the paths that stay public carry no permission by decision (ADR-030) and are asserted separately, each answering 200 rather than merely escaping 401 and 403 (`LedgerAuthorizationIT.Given_a_public_path_When_requested_with_no_token_Then_it_serves_200`). The assistant's test inspects the routes `portfolio_router` declares — today every business route the assistant serves — so a route mounted any other way would still need a valid token, from `BearerAuthentication`, but would escape both the permission check and this test. **The gateway grants no permission at all**: it authorises nothing beyond "has a session" and leaves every permission to the service that answers. It denies any path it was not told to allow (`anyRequest().denyAll()` in `GatewaySecurityConfiguration`), shown for one unrouted path by `GatewaySecurityTest.Given_a_signed_in_session_When_an_unrouted_path_is_requested_Then_it_is_denied_403`, but no gateway test fails the build for an unclassified endpoint |

## Non-functional requirements

| Concern | Requirement | Status |
|---|---|---|
| Authentication | Every request that reaches business data is authenticated, and no switch turns that off. The browser signs in with OIDC at a backend-for-frontend gateway, which keeps access and refresh tokens on the server and gives the browser only an `HttpOnly` session cookie and a CSRF token. The ledger and the assistant each accept only a bearer JWT that Keycloak issued for their own audience, checked against an injected clock, and refuse a missing or invalid token with 401. The assistant authenticates to the ledger with client credentials, as the `assistant` role, which may only read; the client that does so is [#62](https://github.com/NaimElijah/FinAssist/issues/62). As defence in depth, every service still binds loopback by default, refuses a `Host` that is not allow-listed, and accepts writes only as `application/json` ([ADR-030](../ADRs/ADR-030-oidc-authentication-through-a-bff-gateway.md), [ADR-031](../ADRs/ADR-031-keycloak-as-the-identity-provider.md)) | **Enforced on all three services.** The gateway signs the browser in through OIDC with PKCE, keeps the access and refresh tokens in its server-side session, gives the browser only an `HttpOnly` `FINASSIST_SESSION` cookie and a script-readable CSRF token, answers an unauthenticated request 401 rather than redirecting it, and denies every path it was not told to allow; `GatewaySecurityTest`, `KeycloakRegistrationTest`, and `GatewayLoginFlowIT` for a full code-plus-PKCE sign-in, the relay and a refused refresh. Each of the three calls it makes to Keycloak — the code exchange, the `refresh_token` grant and the JWK-set fetch — is bounded by Boot's `spring.http.clients.*` budget, so a Keycloak that accepts a connection and then stops answering cannot park a request for ever; `GatewayKeycloakTimeoutIT`. Keycloak's own settings — no self-registration, the password policy, a forced password change and TOTP at the owner's first sign-in, and each client's audience — are pinned by `KeycloakRealmTest` and proved against the real image by `KeycloakRealmIT`. The ledger validates every bearer token itself and denies by default: `LedgerSecurityConfiguration` builds a `JwtDecoder` against Keycloak's JWKS, checks the issuer, the audience, `sub` and the token's lifetime against an injected clock — `exp` and `iat` are each required, not merely honoured when present, so a signed token carrying neither is refused exactly as the assistant refuses it — and answers a missing or rejected token 401, and a JWKS source that cannot be reached, cannot be read as a key set, or holds no key 503, never a 500 (ADR-037); `LedgerAuthorizationIT`, `LedgerKeySourceOutageIT`, `LedgerJwksBodyTest`, `LedgerTokenValidationTest`. The assistant now does the same: `BearerAuthentication`, a pure ASGI middleware, verifies every request's token through `TokenVerifier` before routing runs and denies by default outside `/health`, `/ready` and `/openapi.json`, answering a missing or rejected token 401, and a JWKS source that cannot be reached, cannot be read as a key set, or holds no key 503, never a 500 (ADR-037), and logging only the reason category, never the token; `test_auth.py`, `test_authorization.py`. The verifier the composition root builds when nothing supplies one is exercised too, against a real local JWKS endpoint, so a swapped issuer or JWKS URI fails the build rather than the owner's first sign-in; `test_auth.py::Given_no_token_verifier_When_create_app_builds_its_own_Then_it_accepts_a_correctly_issued_token`. The defence in depth already holds: each service binds `127.0.0.1` by default. The ledger's `HostAllowListFilter` refuses an un-allow-listed `Host` with 403 ahead of every other filter, and every write `consumes` only `application/json`; `HostAllowListFilterTest`, `LedgerApplicationIT`. The assistant's `HostAllowList` does the same ahead of every route, including ahead of authentication, and its one write, pricing a disposal, accepts only `application/json`; `test_portfolio_api.py`, `test_authorization.py`. The gateway's own `HostAllowListFilter` — a deliberate copy of the ledger's, because the two deployables share no code — refuses the same way, ahead of every other registered filter; `HostAllowListFilterTest`, `GatewayApplicationIT` |
| Authorization | Each service decides what a caller may do from its own table of roles and permissions, and denies anything no rule admits. `owner` reads and writes. `viewer` only reads. `assistant`, the assistant's service principal, only reads the ledger and has no access to the assistant. A caller without the permission a request needs is refused with 403. An endpoint that no rule classifies fails the build rather than shipping open ([ADR-032](../ADRs/ADR-032-role-based-authorization-per-service.md)) | **Enforced on the ledger and the assistant.** `LedgerPermissions` grants `ledger:read` and `ledger:write` from a token's `roles` claim — `owner` gets both, `viewer` and `assistant` get only the read permission, and an unknown role, a missing `roles` claim or one that is not a list grants nothing. Every `GET /api/v1/**` route needs `ledger:read` and every `POST /api/v1/**` route needs `ledger:write`; anything else, including `HEAD` and `OPTIONS`, is denied outright. `LedgerAuthorizationIT` checks this against the ledger's full, named inventory of endpoints, so an endpoint added without a rule fails the build rather than shipping open. The assistant grants `portfolio:read` to `owner` and `viewer` from the same `roles` claim, read defensively onto `Principal` by `TokenVerifier` and never re-parsed from the raw token; `assistant`, an unknown role, a missing `roles` claim or one that is not a list all grant nothing, exactly as the ledger's table refuses them. `RequirePermission`, built by the composition root and passed into `portfolio_router` as `require`, denies each of the three portfolio routes 403 without it — the disposal route checks it before the JSON-media-type check, so a caller with no permission is refused for who they are before how they asked. `test_authorization.py` checks this against every route the router declares, so a route added without a permission dependency fails the build rather than shipping open |
| Secrets | Never in the repository, logs or error messages. `.env` is git-ignored; `.env.example` carries keys with no secret values — every secret is blank, and only non-secret defaults are filled in | Holds. **Enforced** for the realm file: every client secret and credential in `ops/keycloak/finassist-realm.json` is an unresolved `${VAR}` placeholder with no default (`KeycloakRealmTest`), and `docker-compose.yml` refuses to start without each one (`:?`), since Keycloak would otherwise import the placeholder text itself. The gateway has no default for `GATEWAY_CLIENT_SECRET` and refuses to start without it (`KeycloakRegistrationTest`) |
| Data handling | The owner's financial data and `ANTHROPIC_API_KEY` are redacted at the logging boundary, not at each call site | **Enforced** for secrets — `observability.redact_secrets`, `logger.ts`; `test_observability.py`, `logger.test.ts`. The redacted key names include the credentials authentication brought — `authorization`, `cookie`, `csrf`, `xsrf`, `session`, `bearer` and `jwt` — on the assistant and the web client alike; `test_auth.py::Given_the_new_auth_redaction_fragments_When_a_field_matches_Then_its_value_is_redacted`, `logger.test.ts`. The two Java services have no redaction processor because they log no credential: the ledger logs a refused token as its reason category alone (`LedgerAuthorizationIT.Given_any_bad_token_When_refused_Then_the_log_never_contains_that_token`), and the gateway logs no header, cookie, token or session value (`GatewayLoginFlowIT.Given_a_login_and_a_refused_refresh_When_the_logs_are_swept_Then_no_token_code_or_session_value_appears`). Financial data is redacted only where a field name marks it, and no code logs a record yet ([ADR-019](../ADRs/ADR-019-opentelemetry-and-structured-logging.md)) |
| Persistence | Ledger data survives restart; migrations are append-only once released | **Enforced** — PostgreSQL 18, one schema per bounded context with no foreign key crossing one, Flyway migrations in a directory per context, run out of order because each context owns a major version ([ADR-035](../ADRs/ADR-035-flyway-runs-out-of-order.md)). Hand-written SQL over `JdbcClient`; the in-memory adapters are gone rather than kept behind a profile. `JdbcAccountRepositoryIT`, `JdbcTransactionRepositoryIT`, `JdbcBudgetingRepositoriesIT`, `JdbcPlanningRepositoriesIT`, `SchemaBoundariesIT`, `LedgerUpgradeIT` ([ADR-006](../ADRs/ADR-006-postgresql-for-the-ledger-store.md), [ADR-024](../ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md)) |
| Observability | Structured logs, health and readiness endpoints, OpenTelemetry rather than a vendor SDK | **Enforced** — `LedgerObservabilityIT`, `test_observability.py`. The ledger and the assistant emit JSON carrying a W3C trace id and export OTLP; telemetry is a no-op when no collector is configured. The gateway is configured the same way — ECS JSON on stdout, OTLP off unless `OTEL_EXPORT_ENABLED`, and health, liveness and readiness probes (`application.yml`) — and `GatewayApplicationIT` and `GatewaySecurityTest` prove its health and readiness probes answer, with no session; no gateway test asserts its log format or its trace export. The ledger's traces now describe its business endpoints too, not only Actuator and probe traffic, since every request passes through the same instrumented dispatcher ([ADR-019](../ADRs/ADR-019-opentelemetry-and-structured-logging.md)) |
| Performance and scale | TODO (@NaimElijah) — single owner, so this is likely a non-requirement; state it explicitly rather than leaving it open | Undecided |
| Availability | TODO (@NaimElijah) — no deployment target chosen; it runs locally only | Undecided |
| Compliance | TODO (@NaimElijah) — depends on whether data ever leaves the owner's machine | Undecided |
| Auditing | The append-only journal is the audit record; a recorded transaction is never rewritten; every row written from `V1_2` onward records who wrote it | **Enforced in the schema**, as ADR-020 required of the commit that landed the first repository. `V1_1__accounting_schema.sql` refuses an `UPDATE` or `DELETE` on a transaction, its entries or its states, and every transition appends a row; `JournalIsAppendOnlyIT` proves it by issuing both statements. **"Who" is enforced from `V1_2__journal_recorded_by.sql` onward.** `journal_transaction` and `journal_transaction_state` each gain a nullable `recorded_by`, checked `NOT VALID` so historical rows stay `NULL` while every new row must name a non-blank subject of at most 255 characters; `TransactionController` builds it from `java.security.Principal#getName()` — the validated token's `sub` — and passes it as an `Actor` alongside the command, never inside one (ADR-021 still holds). `JdbcTransactionRepositoryIT` proves a transaction recorded by one subject and cleared by another stores each writer on its own row; `JournalIsAppendOnlyIT` proves a new insert without a recorder is refused; `JournalActorMigrationIT` runs `V1_2` against real pre-`V1_2` accounting rows and leaves them naming nobody; `LedgerUpgradeIT` starts the ledger itself against the migration history a real database holds — `1.1`, `2.1`, `3.1` — so the upgrade is proven to succeed rather than assumed ([ADR-035](../ADRs/ADR-035-flyway-runs-out-of-order.md)). Stored, not yet exposed through the API ([#63](https://github.com/NaimElijah/FinAssist/issues/63)). Budgeting and planning stay unattributed: they hold intent, not fact ([ADR-015](../ADRs/ADR-015-planning-as-a-sixth-bounded-context.md)) ([ADR-020](../ADRs/ADR-020-audit-trail-follows-persistence.md), [ADR-033](../ADRs/ADR-033-the-journal-records-who.md)) |

## Non-goals

Stated so they are not re-proposed:

- **Not multi-tenant.** One owner, one dataset. Sign-in accounts exist only to grant a role over
  that dataset; sharing and per-tenant isolation stay out of scope.
- **Not financial advice.** The assistant reports on and explains the owner's own data. It
  is not a recommendation engine, and nothing it produces should be treated as advice.
- **Not a broker or payment system.** FinAssist never moves money, holds funds, or executes
  a trade. It records and analyses.
- **Not a bank aggregator, for now.** Automatic institution sync is not in scope; see
  [Open questions](#open-questions).

## Open questions

| Question | Owner |
|---|---|
| How do the four capability areas relate — one product, or a tracker with features layered on? This drives whether the split in [ADR-001](../ADRs/ADR-001-polyglot-java-python-split.md) is carrying its weight, and it now also decides whether `planning` stays in the ledger ([ADR-015](../ADRs/ADR-015-planning-as-a-sixth-bounded-context.md)) | @NaimElijah |
| Manual entry is the first source of transactions. Does file import (CSV/OFX) or institution sync follow it, and which comes first? | @NaimElijah |
| Where does market data come from, and does its licence permit this use? The `marketdata` context exists with an anti-corruption layer and no client behind it, waiting on this | @NaimElijah |
| Does the assistant send the owner's financial data to a third-party LLM API, and is that acceptable? This is the project's central privacy decision and it needs an ADR | @NaimElijah |
| Is the ledger single-currency or multi-currency? Multi-currency changes the domain model fundamentally and is far cheaper to decide now than later | @NaimElijah |
| Should a projection open at the start of its horizon rather than at today's balance, and should a flow dated on a step boundary count in that step? | @NaimElijah |
| Should a recurring planned flow be allowed to have no end? Today every recurrence ends on a date or after a number of occurrences (`Recurrence`) | @NaimElijah |
| After a transaction is recorded, which account's page should open? Today the web client moves to the first entry's account (`RecordTransactionPage`) | @NaimElijah |

*What authenticates the assistant to the ledger?* was answered on 2026-09-16: the client
credentials grant, as the `assistant` role, which may only read the ledger
([ADR-032](../ADRs/ADR-032-role-based-authorization-per-service.md)). The client that uses it is
still to be built ([#62](https://github.com/NaimElijah/FinAssist/issues/62)).
