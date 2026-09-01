# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Entries are generated from the [Conventional Commit](https://www.conventionalcommits.org/)
history; write the commit message well and this file writes itself.

## [Unreleased]

### Added

- **An application layer in all six bounded contexts.** Use cases behind ports, wired by
  constructor and backed by in-memory adapters that are a development seam rather than a store.
  In the ledger: opening an account, recording a transaction and moving it through its
  lifecycle, reading a balance and the journal, defining and reviewing a budget, planning a
  flow, naming a strategy, projecting it and comparing strategies. In the assistant: reading and
  valuing a position, disposing of units, fetching a quote and refusing a stale one, and
  answering a question that is rejected when it states a figure it cannot cite. See
  [ADR-021](docs/ADRs/ADR-021-application-layer-shape.md).
- **Two requirements that could never have been domain rules are now enforced.** An entry must
  be in its account's currency — an `Entry` holds an `AccountId`, not an `Account`, so checking
  it always needed a repository — and an account name is unique across the chart of accounts,
  which is a property of the set rather than of any value. Both had sat marked `TODO` with that
  explanation since the domain landed.
- **A composition root in the ledger**, `io.github.naimelijah.finassist.ledger.composition`,
  mirroring the one the assistant has had since ADR-019. It holds the Spring wiring and the two
  translations that carry a figure between contexts, because the slice rule has no layer
  qualifier and leaves nowhere else legal. Its exemption is one-way and enforced in both
  directions. See [ADR-022](docs/ADRs/ADR-022-ledger-composition-root.md).
- **Two more live ArchUnit rules**, taking the ledger to seven:
  `no_context_depends_on_the_composition_root`, and
  `the_application_layer_depends_on_no_framework` — which turns a promise `LedgerApplication`
  had made since the scaffold into a build failure. Each was confirmed to fail against a
  violation planted in a deep module, then reverted.
- **Domain events in `accounting` and `planning`**, raised by the application service rather
  than the aggregate, published through an out-port and recorded in an append-only log. Nothing
  subscribes; [ADR-023](docs/ADRs/ADR-023-domain-events-in-the-ledger.md) records that as the
  accepted cost and names the trigger that discharges it.

### Fixed

- **`DisposeUnits` sorts lots by acquisition date before consuming them.** `consume_fifo` takes
  them in the order it is given and documents that the ordering is the caller's responsibility,
  so a caller handing it newest-first lots got a LIFO cost basis in silence, with a plausible
  number and no error — the README called it the sharpest edge in the domain. A test now hands
  it lots in the wrong order and pins the difference between a 1000.00 cost of sale and a
  2000.00 one.
- **`AccountId`'s Javadoc no longer contradicts the rule it cites.** It claimed budgeting holds
  `AccountId` values; `contexts_do_not_depend_on_each_other` forbids budgeting from naming any
  accounting type, which is why `AccountRef` exists.

- Observability across all three codebases: JSON logs on stdout carrying a W3C trace id, OTLP
  trace export that degrades to a no-op when no collector is configured, and Prometheus metrics
  on the ledger. The assistant gained its first entry point — a composition root serving
  `/health` and `/ready` — and with it the redacting logging boundary the requirements had
  listed as missing. The web client generates the `traceparent` the backends continue, logs
  through a single boundary, and no longer blanks the page silently on a render error. A
  collector, Prometheus and Grafana run behind a `monitoring` Compose profile with the
  dashboard provisioned from files. See
  [ADR-019](docs/ADRs/ADR-019-opentelemetry-and-structured-logging.md) and
  [ADR-020](docs/ADRs/ADR-020-audit-trail-follows-persistence.md), which records that the
  append-only journal is the audit trail and that it lands with the first repository.

- Test infrastructure across all three codebases: coverage measured everywhere and gated
  nowhere, an opt-in PIT mutation profile for the ledger, property-based tests on both
  backends (a seeded generator in Java, Hypothesis run derandomised in Python), the first
  unit tests for the web client, and `contracts/money-vectors.v1.json`, which both `Money`
  implementations are checked against so a divergence fails a build. Two new architecture
  rules forbid `float` and wall-clock reads in an assistant domain module, and the import
  scanner the rules rest on is now itself tested. See
  [ADR-017](docs/ADRs/ADR-017-test-strategy.md) and
  [ADR-018](docs/ADRs/ADR-018-cross-language-money-vectors.md).

- Repository baseline: requirements, architecture and ADR documents, licence, editor and
  formatter configuration, pinned toolchains for both services, Docker Compose entry point,
  and a CI pipeline that gates build, lint, format and dependency audit.
- Web client (`frontend/web`): React 19 on Vite 8 with TypeScript, Vitest, ESLint and
  Prettier, one feature directory per user-facing bounded context.

- Domain layer for all five bounded contexts. Accounting: double-entry `Transaction` with a
  balancing invariant, five account types, a sealed lifecycle, and a derived `Balance`.
  Budgeting: periodic `Budget` with per-category limits and derived remaining. Portfolio:
  lot-based FIFO cost basis. Assistance: an answer stating a figure must cite its evidence.
  Market data: quotes and staleness against a supplied clock. `Money` and `Currency` in both
  languages.

- Planning: a sixth bounded context in the ledger, holding what the owner *intends* rather than
  what happened. `PlannedFlow` with a positive amount, a direction, a recurrence and a
  `Planned → Committed → Realised` lifecycle whose realised state is frozen; `Scenario` as a named
  strategy; `Projection` as a derived, never-stored timeline of cash and invested balances with
  shortfall detection at step boundaries; `Comparison`, which refuses strategies in different
  currencies, horizons, step cadences or names rather than aligning them approximately.

### Fixed

- `Money.times` and `Money.times_rounded` now refuse every non-finite multiplier with the
  `ValueError` their contract promises. An infinity or a signalling NaN escaped as
  `decimal.InvalidOperation` - an `ArithmeticError` a caller catching `ValueError` never
  sees. Only a quiet NaN happened to reach `Money`'s own finiteness check, which made the
  gap look closed when it was not.
- `Money.times_rounded` in the Python kernel no longer fails on a large product. Its decimal
  context precision was a fixed 60 rather than sized to the value, so multiplying two large
  amounts raised `decimal.InvalidOperation` - an `ArithmeticError`, not the `ValueError` the
  module's contract promises - where the Java kernel's unbounded `BigDecimal` succeeded. The
  constructor had already been given this fix; `times_rounded` had not. Both now share one
  sizing rule.
- Voiding a *cleared* transaction no longer accepts a date before it cleared. The guard
  compared the void date against the date the transaction occurred only, so a transaction
  cleared on the 20th could be voided on the 10th and the void predated the clearing it
  reverses. `reconcile` already guarded against its own predecessor this way.

### Changed

- Services moved under `backend/`, and both are now packaged by bounded context rather than
  by layer: `accounting` and `budgeting` in the ledger, `portfolio`, `assistance` and
  `marketdata` in the assistant. A context is one directory with its own hexagon.
- Architecture tests extended to enforce context isolation in all three codebases; each rule
  was verified by making it fail.
- The ledger's ArchUnit rules are no longer dormant: `archRule.failOnEmptyShould=false` was
  deleted with the first domain class, and all five now run against real code.

[Unreleased]: https://github.com/NaimElijah/FinAssist/commits/main
