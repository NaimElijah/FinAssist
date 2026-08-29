# ADR-008: `backend/` and `frontend/` as the repository's top-level split

- **Status:** Accepted
- **Date:** 2026-08-29

## Context

Until now the two services sat at the repository root, one directory each. Adding a web
client ([ADR-009](ADR-009-react-vite-spa-for-the-web-client.md)) made the root arrangement a
decision rather than an accident, and four candidate shapes were on the table: leaving the
services flat at the root, an `apps/` plus `libs/` monorepo, a `backend/` plus `frontend/`
tier split, or promoting bounded contexts to top-level deployables.

## Decision

Deployable code is grouped by tier: `backend/ledger`, `backend/assistant`, `frontend/web`.
`contracts/`, `docs/` and the build entry points stay at the root.

Java packages are unaffected — they live below `src/main/java` and are independent of the
path above it. The Python distribution name and import path are likewise unchanged.

## Consequences

**Makes easy**

- One obvious home for a second frontend, or for a service that is neither.
- Tooling that walks a tier at a time — a CI matrix over `backend/*`, say — has a directory
  to walk.

**Makes hard**

- **The grouping carries no domain meaning.** `ledger` and `assistant` are separate bounded
  contexts written in different languages that communicate over a versioned contract; the
  folder implies they are one layer of one system. That is the opposite of what
  [ADR-001](ADR-001-polyglot-java-python-split.md) and
  [ADR-010](ADR-010-bounded-context-map.md) say about them, and a reader who takes the
  directory at face value will start from a wrong model.
- "Backend" is not a word from this project's ubiquitous language, so the root now mixes
  domain vocabulary (`contracts`, the context names beneath) with tier vocabulary.
- Every path in the build, CI, Dependabot and documentation gained a segment, and a uv
  virtualenv had to be recreated because it embeds absolute paths.

This trade-off was raised before the choice was made and accepted deliberately.

## Revisit when

Flatten back to the root if a third deployable appears that is neither backend nor frontend —
a CLI, a scheduled job, a mobile client — because that is the point at which the tier split
stops classifying and starts requiring a judgement call for each new component.

## Alternatives considered

- **Flat at the root** — what the repository already had. Zero migration, shortest paths, and
  it made no claim about how the services relate. Rejected in favour of the tier grouping.
- **`apps/` plus `libs/`** — the conventional monorepo shape, and what Nx or Turborepo expect.
  Rejected: `libs/` is language-fragmented in a polyglot repository, where a TypeScript
  library and a Java library share nothing but a parent folder, and the migration cost is the
  same as this one for less clarity.
- **`contexts/` at the root, one deployable per bounded context** — the most faithful reading
  of DDD. Rejected for the same reason [ADR-007](ADR-007-asymmetric-hexagonal-architecture.md)
  rejected it: four or more deployables for a solo project whose contexts have not yet been
  validated against a working slice.
