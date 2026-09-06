# ADR-025: Integration tests run against a real PostgreSQL, in Testcontainers

- **Status:** Accepted
- **Date:** 2026-09-06

## Context

[ADR-017](ADR-017-test-strategy.md) closed with a deferral that named its own trigger:
*"**Testcontainers and integration tests.** Not rejected, just not yet applicable: there is no
persistence layer, no HTTP adapter and no `*IT.java`, and `verify` binds no failsafe execution.
This becomes a decision the moment ADR-006 is ratified."*

[ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md) ratifies it. The persistence layer is
hand-written SQL, which means the things most likely to be wrong are the things only a database can
answer: whether a trigger actually refuses a `DELETE`, whether a unique index really is what makes
`save` atomic, whether a `numeric` round-trips a JPY amount at scale 0, whether a `CHECK` constraint
rejects the state combination it was written for.

None of that can be tested against a fake. The rules the project already works to say as much:
*"prefer real dependencies in containers over mocks for things I do not own"*, and ADR-017's own
position that a test exists to state a rule rather than to reach a line.

## Decision

**Database-backed tests run against a real PostgreSQL in Testcontainers, on the same pinned image
as Compose** — `postgres:18.6-trixie`. One image tag for the whole project means the thing CI tests
is the thing a developer runs.

**They are named `*IT.java` and bound to `maven-failsafe-plugin`**, so `mvn test` stays fast and
needs no Docker, and `mvn verify` runs everything. `make test-ledger` already calls `verify`, so the
Makefile and CI inherit this without a new target.

**Docker is required to run the ledger's suite, and its absence fails the build.** It does not skip.
A suite that goes green having proven nothing about the SQL is worse than one that refuses to run,
because only the second tells you something is missing.

**Three existing Spring tests become integration tests.** `LedgerApplicationTest`,
`LedgerObservabilityTest` and `LedgerCompositionTest` boot an application context, and a context
with a `DataSource` needs a database — `/actuator/health` reports `DOWN` without one. They are
renamed to `*IT` rather than given a mock, because what they assert is that the real wiring works.

**Isolation is a truncate, not a rebuilt schema.** A shared abstract base holds one container for
the whole run and truncates the context tables before each test. `LedgerCompositionTest` can drop
`@DirtiesContext(AFTER_EACH_TEST_METHOD)`, which existed only because the in-memory repository beans
were singletons holding state; the truncate replaces it and the suite gets faster rather than
slower.

**The unit suite keeps no database at all.** The application services are tested through their ports
with Mockito, as ADR-021 intended when it made them plain objects taking their ports as constructor
arguments. Deleting the in-memory repositories removed the only production code that existed to
support a test.

**PIT's scope does not change.** The JDBC adapters stay out of `targetClasses`: PIT runs surefire
tests only, so every mutant planted in an adapter would report as uncovered by construction and fill
the report with noise no test change could clear. That is the same reasoning that already excludes
`LedgerConfiguration`, and ADR-017's decision — measured, gated on nothing — is untouched.

## Consequences

**Makes easy**

- Testing the half of the persistence layer that lives in SQL. The append-only trigger, the unique
  index, the `CHECK` constraints and the `numeric` scale behaviour are all asserted against the
  engine that will run them.
- Trusting the schema in review. `JournalIsAppendOnlyIT` and `SchemaBoundariesIT` turn two ADR
  obligations into failing builds rather than prose.
- Keeping the fast suite fast. Surefire runs in seconds and touches nothing outside the JVM.

**Makes hard**

- **Docker is now a prerequisite for `make test-ledger` and `make test`.** A contributor without it
  cannot run the ledger's full suite. CI on `ubuntu-latest` has it; a developer machine may need
  Docker Desktop started first, and the README says so.
- The first run pulls a Postgres image. CI caches nothing between jobs, so that cost is paid per run.
- Integration tests are slower than the unit suite by orders of magnitude, and the temptation will be
  to put logic behind them rather than in a domain that can be tested without one.

**Accepted cost**

- Tests share one container and isolate by truncate, so they cannot run in parallel against the same
  schema without more work. The suite is small; when that stops being true, a container per class or
  a schema per test is the escape hatch.

## Alternatives considered

**Testcontainers, skipped when Docker is absent.** The friendly option, and rejected: `make test`
would report success on a developer machine having exercised none of the SQL, and the difference
between "passed" and "did not run" would be invisible in the place people actually look. If the
tests matter, their absence should be loud.

**An embedded database — H2 or HSQLDB in PostgreSQL mode.** Rejected outright. Every single thing
these tests exist to check is PostgreSQL-specific: `plpgsql` triggers, `DEFERRABLE INITIALLY
DEFERRED` constraints, unconstrained `numeric` scale, `DISTINCT ON`, schemas. An embedded database
would pass tests that prove nothing about production, which is worse than not testing.

**A `services: postgres:` block in the GitHub Actions workflow.** Would work in CI and do nothing
locally, so the pipeline and a developer's machine would diverge — precisely what ADR-005 put the
Makefile in the middle to prevent. Testcontainers gives both the same database from the same tag.

**Reusing the Compose `db` container for tests.** Rejected: tests would share a database with
whatever the developer was doing, and the truncate between tests would delete their data.

**No integration tests; unit-test the row mappers only.** Rejected. The mappers are the part least
likely to be wrong. The trigger, the index and the constraints are the part worth testing, and none
of them exists in Java.

## Revisit when

- **The suite gets slow enough to notice** — move to a schema per test class, or a container per
  class, before reaching for skips.
- **An inbound HTTP adapter lands** (ADR-004). It brings its own `*IT` tests, and this record already
  gives them a home and a naming convention.
- **CI runtime becomes a problem.** The escape hatch is caching the image, not dropping the tests.
- **A contributor other than the owner appears** and Docker is a genuine barrier for them. That is a
  real cost to weigh; it is not a reason to weaken the tests today, for one developer who has it.
