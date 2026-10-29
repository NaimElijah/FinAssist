# ADR-010: The context map, and context-first packaging

- **Status:** Accepted, extended by [ADR-015](ADR-015-planning-as-a-sixth-bounded-context.md) and
  [ADR-046](ADR-046-architecture-rules-read-the-code-never-load-it.md), corrected by
  [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md)
- **Date:** 2026-08-29

Extends [ADR-007](ADR-007-asymmetric-hexagonal-architecture.md), which decided that both
services are hexagonal and only the ledger uses tactical DDD. That decision stands; what
changes here is how the code inside each service is filed, and the illustrative package
listing in ADR-007 is superseded by this record.

## Context

Both services were packaged **layer first** — `domain/`, `application/`, `adapter/` at the
top — with `account` and `transaction` sitting inside `domain/` as *aggregates*. There were no
bounded contexts in the code at all, only in conversation, and an aggregate is a finer unit
than a context: *Accounting* contains both `Account` and `Entry`.

Layer-first packaging scatters one context's code across three or more distant directories.
Extraction becomes a scavenger hunt, and nothing structural resists a cross-context import —
the boundary exists only as long as everyone remembers it.

## Decision

### The contexts

| Context | Owner | Holds |
|---|---|---|
| `accounting` | `backend/ledger` (Java) | `Account`, `Entry`, `Balance` — the record of truth |
| `budgeting` | `backend/ledger` (Java) | `Budget`, `Category`, `CategorisationRule` |
| `portfolio` | `backend/assistant` (Python) | `Holding`, `Position`, `Valuation` |
| `assistance` | `backend/assistant` (Python) | `Question`, `Evidence`, `GroundedAnswer` |
| `marketdata` | `backend/assistant` (Python) | `Instrument`, `Quote` — behind an ACL over a feed that is **not yet chosen** |

### The relationships

- `accounting` is **upstream** of everything. It owns the records and publishes them as a
  Published Language ([ADR-004](ADR-004-http-openapi-service-contract.md)).
- `budgeting` references accounts **by identifier only**, never by object. Budgets and ledger
  entries have separate lifecycles and separate invariants.
- `portfolio` consumes `accounting` through the published contract and `marketdata` through a
  port, translating both into its own model at its anti-corruption layer.
- `marketdata` fronts an external price feed. Which feed, and whether its licence permits this
  use, is an open question in [requirements](../requirements/requirements.md#open-questions);
  the boundary exists so that answering it later touches one package.
- The web client mirrors four of the five as `src/features/*`. `marketdata` has no feature: it
  has no user-facing surface.

### The packaging

Context first, hexagon inside. A context is **one directory**:

```
backend/ledger/…/ledger/accounting/{domain,application,adapter}/
backend/assistant/src/finassist_assistant/portfolio/{domain,application,adapters}/
frontend/web/src/features/portfolio/
```

Each service keeps an in-language `shared/` kernel — `Money`, `Currency`, `Period` — which is
the only package every context may use. There is still **no shared kernel across languages**;
`Money` is implemented once per language, as ADR-007 requires.

### The rule that makes this real

**No context may depend on another.** Where two contexts must collaborate, the answer is a
published port and an explicit translation, never an import. This is enforced in all three
codebases, and each rule was verified by introducing a deliberate violation and confirming the
build failed:

| Codebase | Rule |
|---|---|
| Ledger | `LedgerArchitectureTest.contexts_do_not_depend_on_each_other` (ArchUnit slices, `shared` exempt) |
| Assistant | `test_architecture.py::Given_a_context_When_imported_Then_it_loads_no_other_context` |
| Web | `architecture.test.ts` — features may not import each other; `shared` may not import a feature |

## Consequences

**Makes easy**

- A context can be extracted into its own service by moving a directory — the migration path
  ADR-001 named as its escape hatch is now a `git mv` rather than a rewrite.
- Deleting a capability is deleting a folder.
- A new context inherits the layer rules without editing a test: the patterns match
  `..domain..` rather than a fixed path.
- The same five words name the same five things across Java, Python and TypeScript.

**Makes hard**

- Deeper nesting, and longer import paths.
- Five contexts is more structure than a project with no domain code strictly needs; some may
  turn out to be wrong once a real slice exists.
- `shared` needs discipline. It is exempt from the isolation rule, so it is the one place
  where accidental coupling can accumulate unchallenged.
- Aggregate boundaries inside each context are still undesigned, and no test can catch getting
  those wrong.

## Revisit when

Merge two contexts if, after the first working slice, they turn out to change together every
time — a context that never changes alone is not a context. Split one if a single context
accumulates two vocabularies for the same word.

## Alternatives considered

- **Layer first, contexts nested inside each layer** — the smallest diff from what existed,
  and it would have left the layer rules untouched. Rejected: a context's code would live in
  three distant trees, extraction would be manual reassembly, and no structural rule could
  express "accounting must not touch budgeting".
- **Contexts only inside `domain/`, layers flat elsewhere** — the most literal reading of
  "file the domain by context". Rejected: `application/` and `adapter/` become dumping grounds
  and the asymmetry misleads.
- **Three contexts, folding budgeting into accounting** — less ceremony now. Rejected:
  budgeting rules and ledger invariants would share a package and start borrowing each other's
  internals, which is the failure this record exists to prevent.
- **Four contexts, without `marketdata`** — honest while no feed is chosen. Rejected because
  prices arriving from outside need an anti-corruption layer wherever they land, and giving
  them one now is cheaper than retrofitting it into `portfolio` later.
