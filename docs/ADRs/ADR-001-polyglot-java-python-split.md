# ADR-001: Split FinAssist into a Java ledger and a Python assistant

- **Status:** Accepted, corrected by
  [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md)
- **Date:** 2026-08-29

## Context

FinAssist covers three capability areas at once: a personal finance tracker, an LLM-backed
assistant answering questions over the owner's own financial data, and market/portfolio
analysis. See [requirements](../requirements/requirements.md) for the scope as it stands.

Those areas pull in opposite directions:

- The **ledger** is invariant-heavy. Balances, legal transaction state transitions,
  categorisation rules and money arithmetic are the kind of thing a strong static type
  system can make unrepresentable rather than merely tested.
- The **assistant** and the **analysis** are ecosystem-heavy. The mature LLM tooling
  (SDKs, evaluation, tracing) and the numerical stack (pandas, numpy, scipy) are Python
  libraries with no Java peer. Java would mean hand-rolling numerical code and betting on a
  much younger `anthropic-java` SDK.

A single-language repository was the alternative on the table, and the analysis at the time
recommended **Python only**: two of the three capability areas are Python-shaped, one
toolchain is materially cheaper for a solo maintainer, and `Decimal` plus frozen dataclasses
plus `mypy --strict` plus domain tests were judged sufficient — weaker than sealed types, but
sufficient — for the ledger invariants while the scope is still moving.

That recommendation was considered and not taken. The owner chose the polyglot split.

## Decision

FinAssist is two deployable services in one repository:

- `ledger/` — Java 21, Spring Boot. Owns accounts, transactions, balances and the rules
  that govern them. Sole writer of the ledger store.
- `assistant/` — Python 3.13, FastAPI. Owns the LLM assistant and portfolio analysis.
  Reads the ledger only through the ledger's published contract, never its database.

The contract between them is versioned and lives in [`contracts/`](../../contracts)
(see [ADR-004](ADR-004-http-openapi-service-contract.md)).

## Consequences

**Makes easy**

- Each capability sits in the language with the better library and type story for it.
- The ledger can express illegal states as uncompilable, using records and sealed types.
- The services scale and deploy independently; an analysis workload cannot exhaust the
  ledger's request threads.

**Makes hard**

- Two toolchains, two lint and format configurations, two dependency audits, two CI jobs
  and two release artefacts, from day one and before any feature exists.
- "One command to set up, one to run, one to test" now needs a task runner fronting both;
  see [ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md).
- A change spanning both services needs a versioned contract change and a compatible
  deployment order — a cost a single process does not pay.
- Cross-service behaviour cannot be tested in one process; it needs both services running.

## Revisit when

Collapse to a single language if, after the first working slice, the contract churn between
the two services exceeds the value of the split — concretely, if a majority of feature
branches over a quarter have to touch both `ledger/` and `assistant/` and version the
contract to ship one user-visible change. At that point the cheaper structure is one Python
service with the ledger behind a port, which is why the ledger keeps that port from the
start.

## Alternatives considered

- **Python + FastAPI only** — the recommended option. Rejected by the owner in favour of
  compile-time ledger invariants. See
  [ADR-003](ADR-003-python-fastapi-for-assistant-and-analysis.md) for what Python still
  owns.
- **Java + Spring Boot only** — rejected: no credible pandas/numpy equivalent for the
  analysis work, and a young Anthropic Java SDK for the assistant. Two of three capability
  areas would have been built against the weaker ecosystem.
- **One language, two modules** — cheaper than two services, but does not deliver the
  ecosystem access that motivated the split at all.
