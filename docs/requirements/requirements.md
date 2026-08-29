# FinAssist — requirements

What the system must do. *How* it is built is [architecture](../architecture/architecture.md);
*why* a given solution was chosen is [the ADRs](../ADRs/).

> **Status: skeleton.** The scope below is confirmed. The individual requirements are not
> yet written, and none is enforced in code — there is no application code. Every `TODO`
> is a decision waiting on its owner, not an omission to be filled in by inference.
> Behaviour found in code is evidence of a requirement, never proof of one.

## Purpose and scope

FinAssist is a personal finance system for a single owner managing their own money. It
covers three capability areas, confirmed as all in scope:

1. **Tracking** — accounts, transactions, categorisation and budgets.
2. **Assistance** — natural-language questions answered over the owner's own financial
   data, backed by an LLM.
3. **Analysis** — portfolio and market analysis over holdings.

These are reached through a **web client**, which is in scope. It is the only interface;
there is no CLI and no mobile client.

The three capability areas are realised as five bounded contexts, listed with their owning
service in [ADR-010](../ADRs/ADR-010-bounded-context-map.md). Which capability a requirement
belongs to, and which context enforces it, should agree — where they do not, one of the two is
wrong.

## Functional requirements

Each requirement is stated as something a user or caller can do, and names the test or class
that enforces it once one exists.

### Tracking

The ledger keeps books by **double entry** ([ADR-011](../ADRs/ADR-011-double-entry-bookkeeping.md)).

- Open an account with a name, one of five accounting types, and exactly one currency.
  `Account`
- Record a transaction as two or more entries that sum to zero. An unbalanced set cannot be
  recorded. `Transaction`, `TransactionTest`
- Move a transaction through its lifecycle: clear it once the institution confirms it,
  reconcile it once it is matched to a statement, or void it with a reason.
  `TransactionState`, `TransactionTest`
- Read the balance of an account, derived from its entries. `Balance`, `BalanceTest`
- Define a budget for a period, with one spending limit per category. `Budget`, `BudgetTest`
- See what remains in a budgeted category once spend is taken off, including when overspent.
  `Budget.remaining`, `BudgetTest`
- Assign a category to a transaction by matching its description. `CategorisationRule`

**Not yet decided:** where transactions come from. Manual entry, file import and institution
sync all satisfy the requirements above and none is chosen; see
[Open questions](#open-questions).

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
  `position`, `test_portfolio.py`
- Dispose of units, oldest lots first, and read what those units cost. Selling more than is
  held is refused. `consume_fifo`, `InsufficientHoldingError`
- Read realised gain on a sale, and unrealised gain and market value at a given unit price.
  `realised_gain`, `unrealised_gain`, `market_value`
- Judge whether a market quote is too old to use, against a supplied clock. `is_stale`

**Not yet decided:** where prices come from. The `marketdata` context has the shape a feed must
be translated into and no client behind it.

### Web client

TODO (@NaimElijah) — enumerate. Expected to cover at least: viewing accounts and entries,
setting a budget, seeing a portfolio, and asking a question. One screen area per user-facing
context; `marketdata` has no surface of its own and is visible only through portfolio
figures.

## Business rules and invariants

These are the constraints the domain must enforce, not suggestions the UI should follow.
Two hold already as engineering commitments and are recorded in ADRs; the rest are TODO.

| Rule | Enforced by | Status |
|---|---|---|
| A transaction's entries must sum to zero, in one currency, with at least two of them | `Transaction` constructor; `TransactionTest` | **Enforced** |
| Money is exact decimal — never a floating-point type | `Money` (Java and Python); `LedgerArchitectureTest.money_is_never_floating_point`; `MoneyTest`, `test_money.py` | **Enforced** |
| Amounts in two currencies are never combined | `Money.plus/minus/compareTo`; `CurrencyMismatchException` | **Enforced** |
| An amount more precise than its currency admits is rejected, not rounded | `Money` constructor; `MoneyTest`, `test_money.py` | **Enforced** |
| Account balance is derived, never set directly | `Balance.of`; `Account` has no balance field; `BalanceTest` | **Enforced** |
| A voided transaction keeps its entries but does not count towards a balance | `Transaction.countsTowardsBalance`; `BalanceTest` | **Enforced** |
| Legal transaction state transitions; a reconciled transaction is frozen | Sealed `TransactionState`; `IllegalTransitionException`; `TransactionTest` | **Enforced** |
| An account, and every entry posted to it, has exactly one currency | `Account`; `Transaction` constructor | **Enforced** |
| A budget has one limit per category, all in the budget's currency, each positive | `Budget` and `CategoryLimit` constructors; `BudgetTest` | **Enforced** |
| A budget stores no spend; remaining is derived | `Budget.remaining` takes spend as an argument; `BudgetTest` | **Enforced** |
| Disposing of more units than are held is refused | `consume_fifo`; `InsufficientHoldingError`; `test_portfolio.py` | **Enforced** |
| A claim stating a figure must cite the evidence it came from | `ground`; `test_grounding.py` | **Enforced** |
| A market quote carries an unambiguous instant, and staleness is judged against a supplied clock | `Quote`, `is_stale`; `test_marketdata.py` | **Enforced** |
| No bounded context depends on another | `LedgerArchitectureTest.contexts_do_not_depend_on_each_other`; `test_architecture.py`; `architecture.test.ts` | **Enforced** |
| The ledger is the sole writer of ledger data; the assistant reads only through its API | [ADR-004](../ADRs/ADR-004-http-openapi-service-contract.md) | Proposed — no persistence or client exists yet |
| Uniqueness of an account name within the chart of accounts | — | TODO (@NaimElijah) — needs a repository to enforce against |

## Non-functional requirements

| Concern | Requirement | Status |
|---|---|---|
| Authentication | TODO (@NaimElijah) — single-owner system, but the assistant reaches the ledger over the network, so that hop needs a decided answer | Undecided |
| Secrets | Never in the repository, logs or error messages. `.env` is git-ignored; `.env.example` carries keys with no values | Holds |
| Data handling | The owner's financial data and `ANTHROPIC_API_KEY` are redacted at the logging boundary, not at each call site | TODO (@NaimElijah) — no logging boundary exists |
| Persistence | Ledger data survives restart; migrations are append-only once released | TODO (@NaimElijah) — no schema yet |
| Observability | Structured logs, health and readiness endpoints, OpenTelemetry rather than a vendor SDK | Actuator is on the ledger's classpath; nothing is wired |
| Performance and scale | TODO (@NaimElijah) — single owner, so this is likely a non-requirement; state it explicitly rather than leaving it open | Undecided |
| Availability | TODO (@NaimElijah) — no deployment target chosen; it runs locally only | Undecided |
| Compliance | TODO (@NaimElijah) — depends on whether data ever leaves the owner's machine | Undecided |

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
| Does the web client talk only to the assistant, or also to the ledger directly? Talking to both means two contracts and two auth stories; going through the assistant makes it a gateway for traffic that has nothing to do with assistance | @NaimElijah |
| How do the three capability areas relate — one product, or a tracker with two features layered on? This drives whether the split in [ADR-001](../ADRs/ADR-001-polyglot-java-python-split.md) is carrying its weight | @NaimElijah |
| Where does transaction data come from — manual entry, file import (CSV/OFX), or institution sync? | @NaimElijah |
| Where does market data come from, and does its licence permit this use? The `marketdata` context exists with an anti-corruption layer and no client behind it, waiting on this | @NaimElijah |
| Does the assistant send the owner's financial data to a third-party LLM API, and is that acceptable? This is the project's central privacy decision and it needs an ADR | @NaimElijah |
| Is the ledger single-currency or multi-currency? Multi-currency changes the domain model fundamentally and is far cheaper to decide now than later | @NaimElijah |
| What authenticates the assistant to the ledger? | @NaimElijah |
| Ratify or reject [ADR-004](../ADRs/ADR-004-http-openapi-service-contract.md) and [ADR-006](../ADRs/ADR-006-postgresql-for-the-ledger-store.md) before persistence or client code is written | @NaimElijah |
