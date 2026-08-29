# ADR-004: HTTP with a versioned OpenAPI document as the service contract

- **Status:** Proposed
- **Date:** 2026-08-29

## Context

[ADR-001](ADR-001-polyglot-java-python-split.md) puts a process boundary between the ledger
and the assistant, which makes a wire format and a compatibility story required rather than
optional. The assistant reads ledger data; the ledger never calls the assistant.

The traffic is low volume, request/response, and internal. Both ecosystems have first-class
OpenAPI support: Spring Boot can publish a document, and the assistant already depends on
`httpx` and `pydantic`.

This ADR is **Proposed**: it has not been ratified, and nothing implements it yet.

## Decision

The ledger publishes a JSON-over-HTTP API described by an OpenAPI document committed under
[`contracts/`](../../contracts). That document is the contract. It is versioned in the URL
path (`/api/v1/...`), and a breaking change means a new version served alongside the old.

The assistant is a client of that document and never reads the ledger's database.

## Consequences

**Makes easy**

- One artefact both services can be checked against, and a client that can be generated
  rather than hand-written.
- Debugging with `curl`; no tooling needed to read the wire.
- The database stays private to the ledger, so the ledger's schema is free to change.

**Makes hard**

- JSON over HTTP is slower and larger than a binary protocol, and it carries no schema at
  runtime — a mismatch is caught by validation, not by the transport.
- Someone must keep the committed document and the implementation honest; drift is silent
  unless CI compares them.
- Versioning is manual: a breaking change means running two versions for a period.

## Revisit when

Move to gRPC or an event log if the assistant needs streaming updates rather than polling,
or if analysis workloads start pulling volumes of transaction data where JSON serialisation
becomes a measurable cost rather than a theoretical one.

## Alternatives considered

- **gRPC with protobuf** — a stronger schema and cheaper serialisation, but it adds a code
  generation step to both toolchains for traffic that is neither high volume nor streaming.
- **A shared database** — the cheapest thing to build and the most expensive to own: it
  couples the assistant to the ledger's schema and destroys the ledger's claim to be the
  sole writer.
- **A message broker between the two** — appropriate for asynchronous or event-driven flow;
  the assistant's reads are synchronous and user-initiated, so a broker would add
  infrastructure without matching the interaction.
