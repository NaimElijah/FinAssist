# ADR-002: Java 21 and Spring Boot for the ledger service

- **Status:** Accepted, its Java 21 line held by
  [ADR-045](ADR-045-dependabot-triage-and-toolchain-holds.md), extended by
  [ADR-062](ADR-062-every-kept-dependency-is-justified-and-has-a-revisit-trigger.md), corrected by
  [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md)
- **Date:** 2026-08-29

## Context

Given the split in [ADR-001](ADR-001-polyglot-java-python-split.md), the ledger needs a
language whose type system can carry the domain's invariants: a transaction is in exactly
one legal state, a balance is derived rather than set, and money is never a float.

Java 21 is the installed LTS on the development machine (21.0.9). Spring Boot 4.1.1 is the
current stable release; its baseline is Java 17, so 21 is comfortably supported.

## Decision

The ledger is Java 21 on Spring Boot 4.1.1, built with Maven through the committed wrapper
(`ledger/mvnw`, pinned to Maven 3.9.6).

The service is laid out hexagonally — `domain`, `application` (use cases and the ports they
own), `adapter` (HTTP in, persistence out). The domain imports no framework. Formatting is
Spotless with palantir-java-format, and `spotless:check` gates CI.

Money is `BigDecimal`, never `double`. Transaction state is a sealed type, so an illegal
state fails to compile rather than failing a test.

## Consequences

**Makes easy**

- Records and sealed interfaces make illegal domain states unrepresentable, which is the
  reason this service is not in Python.
- Spring Boot Actuator supplies health and readiness endpoints and a metrics surface
  without bespoke code.
- The Maven wrapper means a clone needs a JDK and nothing else.

**Makes hard**

- A second toolchain to keep current, with its own audit path
  (Dependabot rather than an in-pipeline `pip-audit` equivalent).
- Spring's conveniences invite framework annotations into the domain; nothing enforces the
  dependency direction yet. An ArchUnit rule must be added with the first domain class —
  the Python side already carries its equivalent in
  `assistant/tests/test_architecture.py`.

## Alternatives considered

- **Kotlin on the JVM** — better ergonomics for the same invariants, but adds a language to
  a repository that already has two. Rejected as unjustified complexity.
- **Quarkus or Micronaut** — faster start-up and lower memory than Spring Boot, neither of
  which is a constraint here; Spring's ecosystem and documentation are the larger benefit
  for a solo maintainer.
- **Plain Java with no framework** — would leave health, readiness, configuration binding
  and HTTP routing to be hand-rolled, for no gain the domain can feel.
