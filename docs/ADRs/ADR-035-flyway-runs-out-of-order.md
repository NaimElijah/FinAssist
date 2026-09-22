# ADR-035: Flyway runs out of order, because every context owns a major version

- **Status:** Accepted
- **Date:** 2026-09-22

## Context

**[ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md) gave each bounded context a
migration directory and a major version** — `accounting` 1, `budgeting` 2, `planning` 3 — over
one Flyway history table. That is what keeps two contexts from ever colliding on a version or
sharing a file, and it is the storage-layer half of the slice rule.

**It also guarantees, on its own, that most future migrations arrive out of order.** Flyway
orders a single history by version, and `V1_1__accounting_schema.sql`,
`V2_1__budgeting_schema.sql` and `V3_1__planning_schema.sql` all entered git in one commit
(`ab18bfc`). Every database that has ever run this ledger therefore holds `1.1`, `2.1` and `3.1`.
The next accounting migration is `1.2`, which is *below* `3.1`.

In Flyway 12.4.0, a resolved migration below the highest applied version is classed `IGNORED`
when `outOfOrder` is false, and `validate` — which Boot runs before migrating, `validateOnMigrate`
defaulting to true — then fails:

```
Detected resolved migration not applied to database: 1.2.
To ignore this migration, set -ignoreMigrationPatterns='*:ignored'.
To allow executing this migration, set -outOfOrder=true.
```

**This was found by review, one commit before merge, and not by any test.** It would have
surfaced as the owner's ledger container exiting at start-up on their existing `db-data` volume,
with `FlywayMigrationInitializer` failing the Spring context; the gateway does not depend on the
ledger's health, so it would have started, and every accounting, budgeting and planning screen
would have answered 502 `UPSTREAM_UNAVAILABLE`. No data would have been lost, because Flyway
validates before it migrates — the books would simply have been unreachable until this was fixed.

**The whole suite missed it because every test starts on an empty database.** Each `*IT` runs
Flyway from nothing, where `1.1`, `1.2`, `2.1` and `3.1` apply in version order and nothing can be
out of order. `JournalActorMigrationIT`, the one test written to rehearse the upgrade, migrated to
target `1.1` and then to `latest` — applying `1.2`, `2.1`, `3.1` in that order, which is not a
history any real database has ever had. [ADR-033](ADR-033-the-journal-records-who.md) described
that test as a rehearsal of the owner's upgrade; it is not one, and this record corrects that
claim.

## Decision

**`spring.flyway.out-of-order` is `true` in the ledger's `application.yml`, permanently.**

- **It is what ADR-024's scheme requires to keep working.** With it false, no context but the
  highest-numbered one could ever gain a second migration — the scheme would be usable exactly
  once.
- **Order between contexts carries no meaning here.** No foreign key crosses a schema, and
  `SchemaBoundariesIT` fails the build if one ever does, so there is no dependency for an ordering
  to protect. Order *within* a context is untouched: `1.2` still runs after `1.1`, and Flyway
  still refuses a version it has already applied.
- **Checksum validation is unaffected.** `outOfOrder` changes only how an unapplied lower version
  is classed. A committed migration edited after the fact still fails validation, which is the
  protection that actually matters for an append-only journal (ADR-020, ADR-024).

**`LedgerUpgradeIT` holds the decision.** It copies `V1_1`, `V2_1` and `V3_1` byte for byte out of
the classpath into a directory of their own, applies them with a plain Flyway and no flag set —
so the recorded checksums are the committed files' own, exactly as the owner's database holds
them — seeds a journal transaction in the pre-`V1_2` shape, and only then starts the ledger's own
application context against that database. It asserts that the context starts, that
`flyway_schema_history` records `1.2` *after* `3.1`, that both `recorded_by` columns and both
unvalidated `CHECK`s exist, and that the seeded rows still name no recorder.

## Consequences

**Makes easy**

- Adding a migration to any context, at any time, without renumbering it into somebody else's
  major version.
- Upgrading the owner's existing database: the ledger starts, applies `1.2`, and leaves history
  alone.

**Makes hard**

- **A migration given a genuinely wrong version is now applied silently, instead of failing
  validation.** With `outOfOrder` false, a file misnamed `V1_5` when it belonged at `V3_5` would
  at least have refused to start. Three things guard it instead, and none of them is the
  configuration:
  - **The directory layout.** A migration lives in its context's own directory, and a file whose
    major version does not match its directory is visible at a glance in review — the version and
    the folder name are on the same line of a diff.
  - **The schema it names.** Every migration's DDL is qualified with its context's schema, so a
    misfiled one either creates objects in the wrong schema — which `SchemaBoundariesIT` and the
    repositories' own `*IT`s would show — or references a schema that does not exist yet and fails
    outright.
  - **`LedgerUpgradeIT`.** It is the only test that runs a migration against a history a real
    database actually has, and the place to extend when the applied set changes.
- **A rollback to an earlier release is no safer than before, and no worse.** A database that has
  applied `1.2` and then meets a ledger that does not resolve it sees a `MISSING_SUCCESS`
  migration, which Flyway refuses independently of this setting.

**Accepted cost**

- The protection `outOfOrder=false` offers — "you have forgotten to apply something" — is given
  up for a single-owner system where the answer to that question is always "yes, deliberately,
  because the contexts are numbered independently". It is not given up for a system with several
  databases at different versions; that system does not exist here, and the revisit trigger below
  is the moment it would.

## Alternatives considered

**Renumber `V1_2` into `V3_2` or `V4_1`.** Rejected. It puts an accounting migration under
planning's major version (or invents a fourth context that does not exist), which breaks the one
rule ADR-024 wrote the numbering down for, and it buys nothing: the *next* accounting migration
meets the same wall, and the one after that, until the numbering means nothing at all. It also
makes the version number stop answering "which context does this belong to", the question it
exists to answer.

**`spring.flyway.ignore-migration-patterns: '*:ignored'`**, Flyway's other suggestion in the
message above. **Rejected outright, and it is worse than the failure it silences.** The ledger
would start, having skipped `V1_2` entirely, and every journal write would then fail at runtime
with `column "recorded_by" does not exist` — turning a loud, data-safe start-up failure into a
quiet, broken ledger.

**Baseline the owner's database at a higher version.** Rejected: it would tell Flyway to pretend
`1.2` had already run, which is the `ignore-migration-patterns` failure with extra steps, and it
would have to be re-done by hand on every database rather than being a property of the code.

**Give each context its own Flyway history table and its own `Flyway` bean.** Rejected as
disproportionate today. It would make the ordering problem disappear by construction — three
independent histories, each in order — at the cost of three configurations, three initializers
and a start-up ordering of its own, for a single-owner ledger whose three contexts share one
database. Named here because it is the honest answer if the revisit trigger below ever fires.

## Revisit when

- **More than one database runs this ledger** — a second environment, or a deployment target
  (#61). "A migration you have not applied" stops being always-deliberate the moment two
  databases can legitimately differ, and per-context history tables become worth their cost.
- **A migration is ever found to have been applied under a version it did not belong to.** The
  guards above are review and layout, not the build; one real miss is the evidence that they are
  not enough, and the answer then is per-context histories, not turning this setting off.
