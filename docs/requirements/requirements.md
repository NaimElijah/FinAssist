# FinAssist — requirements

What the system must do. *How* it is built is [architecture](../architecture/architecture.md);
*why* a given solution was chosen is [the ADRs](../ADRs/).

> **Status: the domain and application layers are written, the ledger's store is PostgreSQL, and
> both backend services now have a transport — the web client does not.** The store is
> [ADR-006](../ADRs/ADR-006-postgresql-for-the-ledger-store.md) and
> [ADR-024](../ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md). The transport's decisions
> are recorded in [ADR-026](../ADRs/ADR-026-web-client-calls-both-services-through-one-origin.md),
> [ADR-027](../ADRs/ADR-027-defer-authentication-and-bind-to-loopback.md) and
> [ADR-028](../ADRs/ADR-028-http-wire-conventions.md); the ledger implements them, serving
> accounting, budgeting and planning under `/api/v1`, with its generated OpenAPI document pinned
> by `LedgerContractIT`. The assistant implements them too, serving `portfolio` under `/api/v1`,
> with its generated OpenAPI document pinned by `tests/test_portfolio_api.py` and
> `tests/test_contract.py`; its portfolio data still comes from nowhere real — an opt-in
> development seed aside — because neither a ledger client nor a price feed exists. The web
> client calls neither service. Requirements marked
> **Enforced** name the class and the test that hold them today, so this document can be
> checked rather than trusted. Everything still marked `TODO` is a decision waiting on its
> owner, not an omission to be filled in by inference — behaviour found in code is evidence
> of a requirement, never proof of one.

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

The owner enumerated the web client on 2026-09-14. **None of it is implemented yet**: no endpoint
and no screen exists, so no requirement below names an enforcing test, and each gains one in the
commit that builds it. How the client reaches the two services is
[ADR-026](../ADRs/ADR-026-web-client-calls-both-services-through-one-origin.md), and what it is
built with is [ADR-029](../ADRs/ADR-029-web-client-stack.md).

Through the web client, the owner can:

- Open an account, and see every account with its derived balance.
- Record a transaction by hand, and see an account's transactions with their lifecycle state.
- Clear, reconcile or void a transaction.
- Define a budget for a month with a limit per category, and review spend against it: what is
  overspent, what is unbudgeted, and what is uncategorised.
- Add categorisation rules, in order.
- Plan a flow, and move it through its lifecycle.
- Define a scenario.
- Chart a scenario's projection against chosen cash and invested accounts, with the first
  shortfall marked.
- Compare scenarios against a baseline.
- Look up a holding by symbol, see its position and valuation, and price a disposal as a
  calculation that records nothing.

**One screen area per user-facing context**: accounts and transactions, budgets, planning, and
portfolio. `marketdata` has no surface of its own and is visible only through portfolio figures.

**"Ask a question" is deliberately excluded** until the privacy question in
[Open questions](#open-questions) is answered. An assistance screen needs an LLM adapter behind it,
and wiring one would answer that question by implication.

**The portfolio screens have no real data source yet.** No service records a lot, and no price feed
exists; where prices come from is an [open question](#open-questions).

## Business rules and invariants

These are the constraints the domain must enforce, not suggestions the UI should follow.
Two hold already as engineering commitments and are recorded in ADRs; the rest are TODO.

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
| Where two contexts must collaborate, they do so through a port the consumer owns and a translation outside both | `SpendJournal`, `OpeningPositionProvider`; `AccountingSpendJournal`, `AccountingOpeningPositions`; `LedgerCompositionTest`; `test_wiring.py` | **Enforced** — the ports name no foreign type, and the translations live in a composition root nothing may import back into ([ADR-022](../ADRs/ADR-022-ledger-composition-root.md)) |
| The application layer is wired by constructor, never by a container | `LedgerArchitectureTest.the_application_layer_depends_on_no_framework`; `test_architecture.py::…Then_it_imports_no_framework` | **Enforced** — a promise `LedgerApplication` had made since the scaffold with nothing checking it |
| A recorded journal entry is never updated or deleted | `V1_1__accounting_schema.sql` — a `BEFORE UPDATE OR DELETE` trigger on all three journal tables; `JournalIsAppendOnlyIT` | **Enforced by the database.** The port still exposes no delete, but the guarantee no longer depends on that: the trigger refuses the statement whoever issues it, and a lifecycle change is an `INSERT` into `journal_transaction_state` ([ADR-024](../ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md)) |
| A domain function is given the current time rather than reading it | `test_architecture.py::…Then_it_reads_no_clock`, which matches an uncalled `datetime.now` reference too; `is_stale`, `Projection.of`, `Transaction.clear` all take a date | **Enforced** on the Python side; a convention in the ledger |
| The currency table is the same in both languages | `CurrencyTest`, `test_currency.py` | **Enforced** |
| The ledger is the sole writer of ledger data; the assistant reads only through its API | [ADR-004](../ADRs/ADR-004-http-openapi-service-contract.md); the ledger's published contract is `contracts/ledger-v1.openapi.json`, pinned by `LedgerContractIT` | Accepted; the ledger's side is built and its contract published, but the assistant has no ledger client yet, so nothing holds it to the API |
| An account name is unique within the chart of accounts, ignoring surrounding whitespace and case | `account_normalised_name_key` (atomically); `AccountService.open` (for the message); `DuplicateAccountNameException`; `JdbcAccountRepositoryIT`, `AccountServiceTest` | **Enforced** — the repository claims the normalised name, because a check followed by a write is not a rule: two callers can pass the check together. The typed form is what is stored and displayed |

## Non-functional requirements

| Concern | Requirement | Status |
|---|---|---|
| Authentication | None while FinAssist runs only on the owner's machine. Every service binds loopback by default, a request whose `Host` is not allow-listed is refused, and ledger writes accept only `application/json`, which together keep a hostile page in the owner's own browser out. Assistant-to-ledger authentication is deferred by the same record. Revisit when anything other than the owner's machine can reach a port, a deployment target is chosen, the journal needs an actor, or a second person uses FinAssist ([ADR-027](../ADRs/ADR-027-defer-authentication-and-bind-to-loopback.md)) | **Enforced on both services** — each binds `127.0.0.1` by default. The ledger's `HostAllowListFilter` refuses an un-allow-listed `Host` with 403 ahead of every other filter, and every write `consumes` only `application/json`; `HostAllowListFilterTest`, `LedgerApplicationIT`. The assistant's `HostAllowList` does the same ahead of every route, and its one write, pricing a disposal, accepts only `application/json`; `test_portfolio_api.py` |
| Secrets | Never in the repository, logs or error messages. `.env` is git-ignored; `.env.example` carries keys with no values | Holds |
| Data handling | The owner's financial data and `ANTHROPIC_API_KEY` are redacted at the logging boundary, not at each call site | **Enforced** for secrets — `observability.redact_secrets`, `logger.ts`; `test_observability.py`, `logger.test.ts`. Financial data is redacted only where a field name marks it, and no code logs a record yet ([ADR-019](../ADRs/ADR-019-opentelemetry-and-structured-logging.md)) |
| Persistence | Ledger data survives restart; migrations are append-only once released | **Enforced** — PostgreSQL 18, one schema per bounded context with no foreign key crossing one, Flyway migrations in a directory per context. Hand-written SQL over `JdbcClient`; the in-memory adapters are gone rather than kept behind a profile. `JdbcAccountRepositoryIT`, `JdbcTransactionRepositoryIT`, `JdbcBudgetingRepositoriesIT`, `JdbcPlanningRepositoriesIT`, `SchemaBoundariesIT` ([ADR-006](../ADRs/ADR-006-postgresql-for-the-ledger-store.md), [ADR-024](../ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md)) |
| Observability | Structured logs, health and readiness endpoints, OpenTelemetry rather than a vendor SDK | **Enforced** — `LedgerObservabilityIT`, `test_observability.py`. Both services emit JSON carrying a W3C trace id and export OTLP; telemetry is a no-op when no collector is configured. The ledger's traces now describe its business endpoints too, not only Actuator and probe traffic, since every request passes through the same instrumented dispatcher ([ADR-019](../ADRs/ADR-019-opentelemetry-and-structured-logging.md)) |
| Performance and scale | TODO (@NaimElijah) — single owner, so this is likely a non-requirement; state it explicitly rather than leaving it open | Undecided |
| Availability | TODO (@NaimElijah) — no deployment target chosen; it runs locally only | Undecided |
| Compliance | TODO (@NaimElijah) — depends on whether data ever leaves the owner's machine | Undecided |
| Auditing | The append-only journal is the audit record; a recorded transaction is never rewritten | **Enforced in the schema**, as ADR-020 required of the commit that landed the first repository. `V1_1__accounting_schema.sql` refuses an `UPDATE` or `DELETE` on a transaction, its entries or its states, and every transition appends a row; `JournalIsAppendOnlyIT` proves it by issuing both statements. **"Who" is still absent** — that is blocked on authentication, not on persistence, exactly as ADR-020 predicted ([ADR-020](../ADRs/ADR-020-audit-trail-follows-persistence.md)) |

## Non-goals

Stated so they are not re-proposed:

- **Not a multi-tenant product.** One owner, one dataset. Anything implying user accounts,
  sharing or per-tenant isolation is out of scope.
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
| What authenticates the assistant to the ledger? Deferred by [ADR-027](../ADRs/ADR-027-defer-authentication-and-bind-to-loopback.md) while every service binds loopback; its revisit triggers say when this needs an answer | @NaimElijah |
