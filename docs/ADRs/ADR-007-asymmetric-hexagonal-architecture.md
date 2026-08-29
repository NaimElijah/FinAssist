# ADR-007: Asymmetric hexagonal architecture — tactical DDD in the ledger only

- **Status:** Accepted
- **Date:** 2026-08-29

## Context

[ADR-001](ADR-001-polyglot-java-python-split.md) split FinAssist into a Java ledger and a
Python assistant. That decided *where the boundary is*; it did not decide how either side is
organised internally, nor what sits at the boundary.

The default instinct — and the owner's usual practice — is hexagonal architecture with
tactical DDD throughout. Applied uniformly here it would be half right.

Strategic DDD describes the boundary already: the ledger is the **upstream Supplier**, the
assistant the **downstream Customer**, and the OpenAPI document of
[ADR-004](ADR-004-http-openapi-service-contract.md) is the **Published Language** between
them. What that context map demands, and what a naive split omits, is an
**anti-corruption layer** on the downstream side.

The two services are not alike in the one respect that decides whether tactical DDD pays:

- The **ledger is invariant-dense**. A balance equals the sum of its entries. A transaction
  has legal state transitions and illegal ones. Money must not lose precision. Aggregates,
  value objects and domain events exist precisely to protect constraints like these.
- The **assistant is transformation-dense and invariant-sparse**. "Compute a 30-day rolling
  return over this series" is a pure function. It has no identity, no lifecycle, and no
  invariant to protect. Modelling it with aggregates and repositories produces anemic
  classes wrapping dataframes, plus mapping layers that map nothing — ceremony bought with
  no protection in return.

## Decision

Both services are hexagonal — ports and adapters, dependencies pointing inward. Tactical DDD
is applied **only in the ledger**.

**Ledger (`ledger/`) — full tactical DDD.**

```
domain/account/       Account aggregate root, AccountId
domain/transaction/   Transaction aggregate, sealed TransactionState
domain/shared/        Money, Currency, Period — value objects
domain/event/         Domain events
application/port/in/  Use-case interfaces (commands)
application/port/out/ Repository and gateway ports the domain owns
application/service/  Use-case implementations
adapter/in/web/       Controllers and transport DTOs
adapter/out/persistence/  Persistence entities, mapped to aggregates
```

**Assistant (`assistant/`) — hexagonal, pure-function core, no aggregates.**

```
domain/analysis/          Total, deterministic functions: returns, allocation, drawdown
domain/model/             Frozen dataclasses: Holding, Position, Series
application/port/         LedgerPort, LlmPort as typing.Protocol
application/usecase/      Orchestration only
adapters/inbound/api/     FastAPI routers
adapters/outbound/ledger/ The anti-corruption layer
adapters/outbound/llm/    Anthropic client
```

Three rules follow, and all three are enforced by the build:

1. **Dependencies point inward** in both services.
   `LedgerArchitectureTest` (ArchUnit) and `assistant/tests/test_architecture.py`.
2. **The anti-corruption layer is mandatory.** Ledger DTOs are translated into
   `finassist_assistant.domain.model` types in `adapters/outbound/ledger` and nowhere else.
   No inward layer may import the adapter package — asserted per package by the Python test.
3. **Persistence entities are not aggregates.** JPA's requirements — no-arg constructors,
   mutable fields, lazy proxies — must not reach the domain. `domain_depends_on_no_framework`
   fails the build if `jakarta..` or `org.springframework..` appears in the domain.

There is **no shared kernel** between the services. `Money` is implemented twice, once per
language, and pinned by equivalent tests on both sides.

## Consequences

**Makes easy**

- The ledger's invariants are protected by the type system and by the build, which is the
  entire reason it is written in Java.
- The assistant's analysis code stays testable by calling a function, with no fixtures, no
  container and no mocks.
- The anti-corruption layer keeps the ledger's wire shape out of the assistant's model, so
  the ledger's API can evolve without a cascade.
- Each service can be reasoned about with the vocabulary that fits it.

**Makes hard**

- Two different mental models in one repository. A contributor cannot assume the ledger's
  patterns apply on the Python side, which is why this ADR exists.
- `Money` is duplicated across languages. Divergence is a real risk and is caught only by
  the tests on both sides, never by a compiler.
- The ACL is genuine, unglamorous mapping code that must be written and maintained, and the
  temptation to pass a ledger DTO straight through will recur.
- Aggregate boundaries in the ledger are still undesigned; getting them wrong is the most
  expensive mistake available here, and no test can catch it.

## Revisit when

Introduce tactical DDD into the assistant if the analysis side develops genuine invariants —
a portfolio with rebalancing rules, or holdings with a lifecycle and legal state transitions,
rather than figures computed on demand. Invariants, not size, are the trigger.

## Alternatives considered

- **Symmetric: full tactical DDD in both services** — one mental model for the whole
  repository, which is a real benefit. Rejected because the assistant has no invariants to
  protect: its aggregates would wrap dataframes and its repositories would wrap HTTP calls,
  which is DDD vocabulary without DDD substance.
- **Strategic-first: bounded contexts as the top-level split** (Ledger, Budgeting,
  Portfolio, Assistant, each internally hexagonal, with a context-map document) — the most
  faithful reading of DDD, and it would let Budgeting separate from Ledger later without a
  repository reshuffle. Rejected as premature: it means four deployables for a solo project
  whose bounded contexts are not yet identified. Revisit once the tracking slice exists and
  the seams are visible rather than guessed.
- **Java modular monolith with a Python compute sidecar** — would supersede
  [ADR-001](ADR-001-polyglot-java-python-split.md), keep every bounded context in one
  deployable with a genuine shared kernel, and reduce Python to a stateless function.
  Cheaper to operate and to reason about; rejected because it gives up the independent
  scaling and deployment that motivated the split.
- **Anaemic layering with no ports** — cheaper to write, and it would leave the ledger's
  invariants scattered across services, which is the failure mode both ADR-001 and this
  record exist to avoid.
